package online.devliving.stepbuilder.generator;

import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.ide.util.MemberChooser;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.NonFocusableCheckBox;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.ArrayList;
import java.util.List;

public final class StepBuilderOptionSelector {
    private static final List<SelectorOption> OPTIONS = createGeneratorOptions();

    private StepBuilderOptionSelector() {
    }

    private static List<SelectorOption> createGeneratorOptions() {
        final List<SelectorOption> options = new ArrayList<SelectorOption>(8);

        options.add(
                SelectorOption.newBuilder()
                        .withOption(StepBuilderOption.FINAL_SETTERS)
                        .withCaption("Generate builder methods for final fields")
                        .withMnemonic('f')
                        .build());

        options.add(
                SelectorOption.newBuilder()
                        .withOption(StepBuilderOption.COPY_CONSTRUCTOR)
                        .withCaption("Generate builder copy constructor")
                        .withMnemonic('o')
                        .build());

        options.add(
                SelectorOption.newBuilder()
                        .withOption(StepBuilderOption.WITH_JAVADOC)
                        .withCaption("Add Javadoc")
                        .withMnemonic('c')
                        .withTooltip("Add Javadoc to generated builder class and methods")
                        .build());

        options.add(
                SelectorOption.newBuilder()
                        .withOption(StepBuilderOption.PUBLIC_INTERFACES)
                        .withCaption("Make Interfaces public")
                        .withMnemonic('p')
                        .withTooltip("Make generated interfaces public")
                        .build());
        return options;
    }

    @Nullable
    public static List<PsiFieldMember> selectFieldsAndOptions(final List<PsiFieldMember> members,
                                                              final Project project) {
        if (members == null || members.isEmpty()) {
            return null;
        }

        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return members;
        }

        final JCheckBox[] optionCheckBoxes = buildOptionCheckBoxes();

        final PsiFieldMember[] memberArray = members.toArray(new PsiFieldMember[members.size()]);

        final MemberChooser<PsiFieldMember> chooser = new MemberChooser<PsiFieldMember>(memberArray,
                false, // allowEmptySelection
                true,  // allowMultiSelection
                project, null, optionCheckBoxes);

        chooser.setTitle("Select Mandatory Fields and Options for the Builder");
        chooser.selectElements(memberArray);
        if (chooser.showAndGet()) {
            return chooser.getSelectedElements();
        }

        return null;
    }

    private static JCheckBox[] buildOptionCheckBoxes() {
        final PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();
        //propertiesComponent.setValue(StepBuilderOption.NEW_BUILDER_METHOD.getProperty(), Boolean.toString(true));
        final int optionCount = OPTIONS.size();
        final JCheckBox[] checkBoxesArray = new JCheckBox[optionCount];
        for (int i = 0; i < optionCount; i++) {
            checkBoxesArray[i] = buildOptionCheckBox(propertiesComponent, OPTIONS.get(i));
        }

        return checkBoxesArray;
    }

    private static JCheckBox buildOptionCheckBox(final PropertiesComponent propertiesComponent,
                                                 final SelectorOption selectorOption) {
        final StepBuilderOption option = selectorOption.getOption();

        final JCheckBox optionCheckBox = new NonFocusableCheckBox(selectorOption.getCaption());
        optionCheckBox.setMnemonic(selectorOption.getMnemonic());
        optionCheckBox.setToolTipText(selectorOption.getToolTip());

        final String optionProperty = option.getProperty();
        optionCheckBox.setSelected(propertiesComponent.isTrueValue(optionProperty));
        optionCheckBox.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(final ItemEvent event) {
                propertiesComponent.setValue(optionProperty, Boolean.toString(optionCheckBox.isSelected()));
            }
        });
        return optionCheckBox;
    }
}

