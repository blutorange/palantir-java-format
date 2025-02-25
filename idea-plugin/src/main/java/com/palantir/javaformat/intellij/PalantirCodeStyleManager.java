/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.javaformat.intellij;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Comparator.comparing;

import com.google.common.base.Preconditions;
import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.ChangedRangesInfo;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.impl.CheckUtil;
import com.intellij.psi.impl.source.codeStyle.CodeStyleManagerImpl;
import com.intellij.serviceContainer.NonInjectable;
import com.intellij.util.IncorrectOperationException;
import com.palantir.javaformat.java.FormatterException;
import com.palantir.javaformat.java.FormatterService;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CodeStyleManager} implementation which formats .java files with palantir-java-format. Formatting of all
 * other types of files is delegated to IJ's default implementation.
 */
@SuppressWarnings("PreferSafeLogger")
final class PalantirCodeStyleManager extends CodeStyleManagerDecorator {
    private static final Logger log = LoggerFactory.getLogger(PalantirCodeStyleManager.class);
    private static final String PLUGIN_ID = "palantir-java-format";
    static final IdeaPluginDescriptor PLUGIN = Preconditions.checkNotNull(
            PluginManager.getPlugin(PluginId.getId(PLUGIN_ID)), "Couldn't find our own plugin: %s", PLUGIN_ID);

    private final FormatterProvider formatterProvider = new FormatterProvider();

    private final Project project;

    public PalantirCodeStyleManager(@NotNull Project project) {
        super(project, new CodeStyleManagerImpl(project));
        this.project = project;
    }

    @NonInjectable
    PalantirCodeStyleManager(@NotNull CodeStyleManager original) {
        super(original.getProject(), original);
        this.project = original.getProject();
    }

    @SuppressWarnings("ImmutableMapDuplicateKeyStrategy")
    static Map<TextRange, String> getReplacements(
            FormatterService formatter, String text, Collection<? extends TextRange> ranges) {
        try {
            ImmutableMap.Builder<TextRange, String> replacements = ImmutableMap.builder();
            formatter.getFormatReplacements(text, toRanges(ranges)).forEach(replacement -> {
                replacements.put(toTextRange(replacement.getReplaceRange()), replacement.getReplacementString());
            });
            return replacements.build();
        } catch (FormatterException e) {
            log.debug("Formatter failed, no replacements", e);
            return ImmutableMap.of();
        }
    }

    private static Collection<Range<Integer>> toRanges(Collection<? extends TextRange> textRanges) {
        return textRanges.stream()
                .map(textRange -> Range.closedOpen(textRange.getStartOffset(), textRange.getEndOffset()))
                .collect(Collectors.toList());
    }

    private static TextRange toTextRange(Range<Integer> range) {
        checkState(range.lowerBoundType().equals(BoundType.CLOSED)
                && range.upperBoundType().equals(BoundType.OPEN));
        return new TextRange(range.lowerEndpoint(), range.upperEndpoint());
    }

    @Override
    public void reformatText(PsiFile file, int startOffset, int endOffset) throws IncorrectOperationException {
        if (overrideFormatterForFile(file)) {
            formatInternal(file, ImmutableList.of(new TextRange(startOffset, endOffset)));
        } else {
            super.reformatText(file, startOffset, endOffset);
        }
    }

    @Override
    public void reformatText(PsiFile file, Collection ranges) throws IncorrectOperationException {
        if (overrideFormatterForFile(file)) {
            formatInternal(file, ranges);
        } else {
            super.reformatText(file, ranges);
        }
    }

    @Override
    public void reformatTextWithContext(PsiFile psiFile, ChangedRangesInfo changedRangesInfo)
            throws IncorrectOperationException {
        reformatTextWithContext(psiFile, changedRangesInfo.allChangedRanges);
    }

    @Override
    public void reformatTextWithContext(PsiFile file, Collection ranges) {
        if (overrideFormatterForFile(file)) {
            formatInternal(file, ranges);
        } else {
            super.reformatTextWithContext(file, ranges);
        }
    }

    @Override
    public PsiElement reformatRange(PsiElement element, int startOffset, int endOffset)
            throws IncorrectOperationException {
        // Preserve the fallback defined in CodeStyleManagerImpl
        return reformatRange(element, startOffset, endOffset, false);
    }

    @Override
    public PsiElement reformatRange(
            PsiElement element, int startOffset, int endOffset, boolean canChangeWhiteSpacesOnly) {
        // Only handle elements that are PsiFile for now -- otherwise we need to search for some
        // element within the file at new locations given the original startOffset and endOffsets
        // to serve as the return value.
        PsiFile file = element instanceof PsiFile ? (PsiFile) element : null;
        if (file != null && canChangeWhiteSpacesOnly && overrideFormatterForFile(file)) {
            formatInternal(file, ImmutableList.of(new TextRange(startOffset, endOffset)));
            return file;
        } else {
            return super.reformatRange(element, startOffset, endOffset, canChangeWhiteSpacesOnly);
        }
    }

    /** Return whether or not this formatter can handle formatting the given file. */
    private boolean overrideFormatterForFile(PsiFile file) {
        return StdFileTypes.JAVA.equals(file.getFileType())
                && PalantirJavaFormatSettings.getInstance(getProject()).isEnabled();
    }

    private void formatInternal(PsiFile file, Collection<? extends TextRange> ranges) {
        ApplicationManager.getApplication().assertWriteAccessAllowed();
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(getProject());
        documentManager.commitAllDocuments();
        CheckUtil.checkWritable(file);

        Document document = documentManager.getDocument(file);

        if (document == null) {
            return;
        }
        // If there are postponed PSI changes (e.g., during a refactoring), just abort.
        // If we apply them now, then the incoming text ranges may no longer be valid.
        if (documentManager.isDocumentBlockedByPsi(document)) {
            return;
        }

        format(document, ranges);
    }

    /**
     * Format the ranges of the given document.
     *
     * <p>Overriding methods will need to modify the document with the result of the external formatter (usually using
     * {@link #performReplacements(Document, Map)}.
     */
    private void format(Document document, Collection<? extends TextRange> ranges) {
        PalantirJavaFormatSettings settings = PalantirJavaFormatSettings.getInstance(getProject());
        FormatterService formatter = formatterProvider.get(project, settings);

        performReplacements(document, getReplacements(formatter, document.getText(), ranges));
    }

    private void performReplacements(final Document document, final Map<TextRange, String> replacements) {
        if (replacements.isEmpty()) {
            return;
        }

        TreeMap<TextRange, String> sorted = new TreeMap<>(comparing(TextRange::getStartOffset));
        sorted.putAll(replacements);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            for (Map.Entry<TextRange, String> entry : sorted.descendingMap().entrySet()) {
                document.replaceString(
                        entry.getKey().getStartOffset(), entry.getKey().getEndOffset(), entry.getValue());
            }
            PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        });
    }
}
