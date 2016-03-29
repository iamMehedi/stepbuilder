package online.devliving.stepbuilder.generator;

import org.jetbrains.annotations.NotNull;

import com.intellij.codeInsight.CodeInsightActionHandler;
import com.intellij.codeInsight.actions.BaseCodeInsightAction;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;

import com.intellij.psi.PsiFile;

/**
 * The IntelliJ IDEA action for this plugin, generates an step builder class.
 */
public class StepBuilderAction extends BaseCodeInsightAction {
    private final StepBuilderHandler handler = new StepBuilderHandler();

    @NotNull
    @Override
    protected CodeInsightActionHandler getHandler() {
        return handler;
    }

    @Override
    protected boolean isValidForFile(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        return handler.isValidFor(editor, file);
    }
}
