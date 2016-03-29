package online.devliving.stepbuilder.generator;

import com.intellij.codeInsight.generation.PsiFieldMember;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class StepBuilderGenerator implements Runnable {

    @NonNls
    private static final String BUILDER_CLASS_NAME = "Builder";
    @NonNls
    private static final String BUILD_STEP_INTERFACE_NAME = "Build";
    @NonNls
    private static final String INTERFACE_NAME_PREFIX = "I";
    @NonNls
    private static final String BUILDER_SETTER_DEFAULT_PARAMETER_NAME = "val";
    @NonNls
    private static final String BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME = "value";
    @NonNls
    private static final String OVERRIDE_ANNOTATION = "java.lang.Override";

    private final Project project;
    private final PsiFile file;
    private final Editor editor;
    private final List<PsiFieldMember> mandatoryFields;
    private final List<PsiFieldMember> optionalFields;
    private final PsiElementFactory psiElementFactory;

    private StepBuilderGenerator(final Project project, final PsiFile file, final Editor editor,
                                 final List<PsiFieldMember> mandatoryFields, final List<PsiFieldMember> optionalFields) {
        this.project = project;
        this.file = file;
        this.editor = editor;
        this.mandatoryFields = mandatoryFields;
        this.optionalFields = optionalFields;
        psiElementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
    }

    public static void generate(final Project project, final Editor editor, final PsiFile file,
                                final List<PsiFieldMember> selectedFields, final List<PsiFieldMember> optionalFields) {
        final Runnable builderGenerator = new StepBuilderGenerator(project, file, editor, selectedFields, optionalFields);
        ApplicationManager.getApplication().runWriteAction(builderGenerator);
    }

    private static EnumSet<StepBuilderOption> currentOptions() {
        final EnumSet<StepBuilderOption> options = EnumSet.noneOf(StepBuilderOption.class);
        final PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();
        for (final StepBuilderOption option : StepBuilderOption.values()) {
            final boolean currentSetting = propertiesComponent.getBoolean(option.getProperty(), false);
            if (currentSetting) {
                options.add(option);
            }
        }
        return options;
    }

    @Override
    public void run() {
        final PsiClass topLevelClass = StepBuilderUtils.getTopLevelClass(project, file, editor);
        if (topLevelClass == null) {
            return;
        }

        final Set<StepBuilderOption> options = currentOptions();

        final List<PsiFieldMember> finalFields = new ArrayList<PsiFieldMember>(); //should't have setters
        final List<PsiFieldMember> nonFinalFields = new ArrayList<PsiFieldMember>(); //should have setters
        final List<PsiFieldMember> optionalNonfinalFields = new ArrayList<PsiFieldMember>();
        final List<PsiFieldMember> mandatoryNonfinalFields = new ArrayList<PsiFieldMember>();

        //generate the interfaces
        //generate optional interface
        final PsiClass optionalInterface = createBuildStepInterface();
        final PsiClassType optionalInterfaceType = psiElementFactory.createType(optionalInterface);

        if(optionalFields != null && !optionalFields.isEmpty()) {
            for (PsiFieldMember fieldMember : optionalFields) {
                if (!fieldMember.getElement().hasModifierProperty(PsiModifier.FINAL)
                        || options.contains(StepBuilderOption.FINAL_SETTERS)) {

                    nonFinalFields.add(fieldMember);
                    optionalNonfinalFields.add(fieldMember);

                    String fieldName = fieldMember.getElement().getName();
                    String methodName = String.format("with%s", StepBuilderUtils.capitalize(fieldName));
                    String paramName = BUILDER_SETTER_DEFAULT_PARAMETER_NAME.equals(fieldName) ?
                            BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME : BUILDER_SETTER_DEFAULT_PARAMETER_NAME;

                    PsiMethod methodStatement = psiElementFactory.createMethodFromText(String.format("%s %s(%s %s);",
                            optionalInterfaceType.getPresentableText(), methodName, fieldMember.getElement().getType().getPresentableText(),
                            paramName), optionalInterface);

                    optionalInterface.add(methodStatement);
                } else {
                    finalFields.add(fieldMember);
                }
            }
        }
        //add build method
        PsiMethod methodStatement = psiElementFactory.createMethodFromText(String.format("%s %s();",
                psiElementFactory.createType(topLevelClass).getPresentableText(), "build"), optionalInterface);

        optionalInterface.add(methodStatement);

        topLevelClass.add(optionalInterface);

        //generate mandatory interfaces
        final List<PsiClassType> mandatoryInterfaceTypes = new ArrayList<>();
        if(mandatoryFields != null && !mandatoryFields.isEmpty()){
            PsiClassType returnType = optionalInterfaceType;

            for(int i = mandatoryFields.size() - 1; i >= 0; i--) {
                PsiFieldMember fieldMember = mandatoryFields.get(i);
                if (!fieldMember.getElement().hasModifierProperty(PsiModifier.FINAL)
                        || options.contains(StepBuilderOption.FINAL_SETTERS)) {

                    nonFinalFields.add(fieldMember);
                    mandatoryNonfinalFields.add(fieldMember);

                    PsiClass mInterface = generateMandatoryInterface(fieldMember, returnType);
                    topLevelClass.add(mInterface);

                    returnType = psiElementFactory.createType(mInterface);
                    mandatoryInterfaceTypes.add(returnType);
                }
                else{
                    finalFields.add(fieldMember);
                }
            }
        }

        //create builder class
        final PsiClass builderClass = findOrCreateBuilderClass(topLevelClass, mandatoryInterfaceTypes, optionalInterfaceType);
        final PsiType builderType = psiElementFactory.createTypeFromText(BUILDER_CLASS_NAME, null);
        //topLevelClass.addAfter(psiElementFactory.createCommentFromText("//regionend", null), builderClass);
        //add a constructor to the class
        final PsiMethod constructor = generateConstructor(topLevelClass, builderType);
        addMethod(topLevelClass, null, constructor, true);
        //topLevelClass.addBefore(psiElementFactory.createCommentFromText("//region Builder", null), constructor);
        //add the fields in Builder
        PsiElement lastAddedField = null;
        for (final PsiFieldMember fieldMember : nonFinalFields) {
            lastAddedField = findOrCreateField(builderClass, fieldMember, lastAddedField);
        }

        for (final PsiFieldMember fieldMember : finalFields) {
            lastAddedField = findOrCreateField(builderClass, fieldMember, lastAddedField);
            PsiUtil.setModifierProperty((PsiField) lastAddedField, PsiModifier.FINAL, true);
        }

        // builder constructor, accepting the final fields
        final PsiMethod builderConstructorMethod = generateBuilderConstructor(builderClass, finalFields, options);
        addMethod(builderClass, null, builderConstructorMethod, false);

        // builder copy constructor or static copy method
        if (options.contains(StepBuilderOption.COPY_CONSTRUCTOR)) {
            final PsiMethod copyBuilderMethod = generateCopyBuilderMethod(topLevelClass, builderType,
                    finalFields,nonFinalFields, options);
            addMethod(topLevelClass, null, copyBuilderMethod, true);
        }

        // builder methods
        final PsiClassType lastInterfaceType;

        PsiElement lastAddedElement = null;
        if(!mandatoryNonfinalFields.isEmpty()) {
            PsiClassType returnType = optionalInterfaceType;

            for (int i = 0; i < mandatoryNonfinalFields.size(); i++) {
                final PsiFieldMember member = mandatoryNonfinalFields.get(i);
                final PsiMethod setterMethod = generateBuilderSetter(returnType, member, options);
                lastAddedElement = addMethod(builderClass, lastAddedElement, setterMethod, false);
                returnType = mandatoryInterfaceTypes.get(i);
            }

            lastInterfaceType = returnType;
        }
        else{
            lastInterfaceType = optionalInterfaceType;
        }

        if(!optionalNonfinalFields.isEmpty()) {
            for (int i = 0; i < optionalNonfinalFields.size(); i++) {
                final PsiFieldMember member = optionalNonfinalFields.get(i);
                final PsiMethod setterMethod = generateBuilderSetter(optionalInterfaceType, member, options);
                lastAddedElement = addMethod(builderClass, lastAddedElement, setterMethod, false);
            }
        }

        //generate the static builder method
        final PsiMethod newBuilderMethod = generateNewBuilderMethod(builderType, finalFields, options, lastInterfaceType);
        addMethod(topLevelClass, null, newBuilderMethod, false);

        // builder.build() method
        final PsiMethod buildMethod = generateBuildMethod(topLevelClass, options);
        addMethod(builderClass, lastAddedElement, buildMethod, false);

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(file);
        CodeStyleManager.getInstance(project).reformat(builderClass);
    }

    private PsiClass createBuildStepInterface(){
        return psiElementFactory.createInterface(INTERFACE_NAME_PREFIX + BUILD_STEP_INTERFACE_NAME);
    }

    /**
     *
     * @param forMember
     * @param returnType - should be an interface type
     * @return
     */
    private PsiClass generateMandatoryInterface(PsiFieldMember forMember, PsiType returnType){
        String capitalizedFieldName = StepBuilderUtils.capitalize(forMember.getElement().getName());
        String methodName = String.format("with%s", capitalizedFieldName);
        String paramName = BUILDER_SETTER_DEFAULT_PARAMETER_NAME.equals(forMember.getElement().getName())?
                BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME:BUILDER_SETTER_DEFAULT_PARAMETER_NAME;

        PsiClass mInterface = psiElementFactory.createInterface(INTERFACE_NAME_PREFIX + capitalizedFieldName);

        PsiMethod fieldMethod = psiElementFactory.createMethodFromText(String.format("%s %s(%s %s);", returnType.getPresentableText(),
                methodName, forMember.getElement().getType().getPresentableText(), paramName), mInterface);

        mInterface.add(fieldMethod);
        return mInterface;
    }

    private PsiMethod generateCopyBuilderMethod(final PsiClass topLevelClass, final PsiType builderType,
                                                final Collection<PsiFieldMember> finalFields,
                                                final Collection<PsiFieldMember> nonFinalfields,
                                                final Set<StepBuilderOption> options) {
        //create the method
        final PsiMethod copyBuilderMethod = psiElementFactory.createMethod("newBuilder", builderType);
        PsiUtil.setModifierProperty(copyBuilderMethod, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(copyBuilderMethod, PsiModifier.PUBLIC, true);

        //add method parameter
        final PsiType topLevelClassType = psiElementFactory.createType(topLevelClass);
        final PsiParameter parameter = psiElementFactory.createParameter("copy", topLevelClassType);
        final PsiModifierList parameterModifierList = parameter.getModifierList();

        copyBuilderMethod.getParameterList().add(parameter);

        //add body to method
        final PsiCodeBlock copyBuilderBody = copyBuilderMethod.getBody();
        if (copyBuilderBody != null) {
            final StringBuilder copyBuilderParameters = new StringBuilder();
            for (final PsiFieldMember fieldMember : finalFields) {
                if (copyBuilderParameters.length() > 0) {
                    copyBuilderParameters.append(", ");
                }

                copyBuilderParameters.append(String.format("copy.%s", fieldMember.getElement().getName()));
            }

            final PsiStatement newBuilderStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s builder = new %s(%s);", builderType.getPresentableText(),
                    builderType.getPresentableText(), copyBuilderParameters.toString()),
                    copyBuilderMethod);
            copyBuilderBody.add(newBuilderStatement);

            addCopyBody(nonFinalfields, copyBuilderMethod, "builder.");
            copyBuilderBody.add(psiElementFactory.createStatementFromText("return builder;", copyBuilderMethod));
        }
        return copyBuilderMethod;
    }

    private PsiMethod generateCopyConstructor(final PsiClass topLevelClass, final PsiType builderType,
                                              final Collection<PsiFieldMember> nonFinalFields,
                                              final Set<StepBuilderOption> options) {

        final PsiMethod copyConstructor = psiElementFactory.createConstructor(builderType.getPresentableText());
        PsiUtil.setModifierProperty(copyConstructor, PsiModifier.PUBLIC, true);

        final PsiType topLevelClassType = psiElementFactory.createType(topLevelClass);
        final PsiParameter constructorParameter = psiElementFactory.createParameter("copy", topLevelClassType);
        final PsiModifierList parameterModifierList = constructorParameter.getModifierList();

        copyConstructor.getParameterList().add(constructorParameter);
        addCopyBody(nonFinalFields, copyConstructor, "this.");
        return copyConstructor;
    }

    private void addCopyBody(final Collection<PsiFieldMember> fields, final PsiMethod method, final String qName) {
        final PsiCodeBlock methodBody = method.getBody();
        if (methodBody == null) {
            return;
        }
        for (final PsiFieldMember member : fields) {
            final PsiField field = member.getElement();
            final PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s%2$s = copy.%2$s;", qName, field.getName()), method);
            methodBody.add(assignStatement);
        }
    }

    private PsiMethod generateBuilderConstructor(final PsiClass builderClass,
                                                 final Collection<PsiFieldMember> finalFields,
                                                 final Set<StepBuilderOption> options) {

        final PsiMethod builderConstructor = psiElementFactory.createConstructor(builderClass.getName());
        PsiUtil.setModifierProperty(builderConstructor, PsiModifier.PRIVATE, true);

        final PsiCodeBlock builderConstructorBody = builderConstructor.getBody();
        if (builderConstructorBody != null) {
            for (final PsiFieldMember member : finalFields) {
                final PsiField field = member.getElement();
                final PsiType fieldType = field.getType();
                final String fieldName = field.getName();

                final PsiParameter parameter = psiElementFactory.createParameter(fieldName, fieldType);
                final PsiModifierList parameterModifierList = parameter.getModifierList();

                builderConstructor.getParameterList().add(parameter);
                final PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                        "this.%1$s = %1$s;", fieldName), builderConstructor);
                builderConstructorBody.add(assignStatement);
            }
        }

        return builderConstructor;
    }

    private PsiMethod generateNewBuilderMethod(final PsiType builderType, final Collection<PsiFieldMember> finalFields,
                                               final Set<StepBuilderOption> options, final PsiType returnType) {
        final PsiMethod newBuilderMethod = psiElementFactory.createMethod("builder", returnType);
        PsiUtil.setModifierProperty(newBuilderMethod, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(newBuilderMethod, PsiModifier.PUBLIC, true);

        final StringBuilder fieldList = new StringBuilder();
        if (!finalFields.isEmpty()) {
            for (final PsiFieldMember member : finalFields) {
                final PsiField field = member.getElement();
                final PsiType fieldType = field.getType();
                final String fieldName = field.getName();

                final PsiParameter parameter = psiElementFactory.createParameter(fieldName, fieldType);
                final PsiModifierList parameterModifierList = parameter.getModifierList();

                newBuilderMethod.getParameterList().add(parameter);
                if (fieldList.length() > 0) {
                    fieldList.append(", ");
                }
                fieldList.append(fieldName);
            }
        }
        final PsiCodeBlock newBuilderMethodBody = newBuilderMethod.getBody();
        if (newBuilderMethodBody != null) {
            final PsiStatement newStatement = psiElementFactory.createStatementFromText(String.format(
                            "return new %s(%s);", builderType.getPresentableText(), fieldList.toString()),
                    newBuilderMethod);
            newBuilderMethodBody.add(newStatement);
        }
        return newBuilderMethod;
    }

    private PsiMethod generateBuilderSetter(final PsiType returnType, final PsiFieldMember member,
                                            final Set<StepBuilderOption> options) {

        final PsiField field = member.getElement();
        final PsiType fieldType = field.getType();
        final String fieldName = field.getName();

        final String methodName = String.format("with%s", StepBuilderUtils.capitalize(fieldName));

        final String parameterName = !BUILDER_SETTER_DEFAULT_PARAMETER_NAME.equals(fieldName) ?
                BUILDER_SETTER_DEFAULT_PARAMETER_NAME :
                BUILDER_SETTER_ALTERNATIVE_PARAMETER_NAME;
        final PsiMethod setterMethod = psiElementFactory.createMethod(methodName, returnType);

        setterMethod.getModifierList().addAnnotation(OVERRIDE_ANNOTATION);

        setterMethod.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);
        final PsiParameter setterParameter = psiElementFactory.createParameter(parameterName, fieldType);

        if (!(fieldType instanceof PsiPrimitiveType)) {
            final PsiModifierList setterParameterModifierList = setterParameter.getModifierList();
        }
        setterMethod.getParameterList().add(setterParameter);
        final PsiCodeBlock setterMethodBody = setterMethod.getBody();
        if (setterMethodBody != null) {
            final PsiStatement assignStatement = psiElementFactory.createStatementFromText(String.format(
                    "%s = %s;", fieldName, parameterName), setterMethod);
            setterMethodBody.add(assignStatement);
            setterMethodBody.add(StepBuilderUtils.createReturnThis(psiElementFactory, setterMethod));
        }
        setSetterComment(setterMethod, fieldName, parameterName, returnType);
        return setterMethod;
    }

    private PsiMethod generateConstructor(final PsiClass topLevelClass, final PsiType builderType) {
        final PsiMethod constructor = psiElementFactory.createConstructor(topLevelClass.getName());
        constructor.getModifierList().setModifierProperty(PsiModifier.PRIVATE, true);

        final PsiParameter builderParameter = psiElementFactory.createParameter("builder", builderType);
        constructor.getParameterList().add(builderParameter);

        final PsiCodeBlock constructorBody = constructor.getBody();
        if (constructorBody != null) {
            for (final PsiFieldMember member : mandatoryFields) {
                final PsiField field = member.getElement();

                final PsiMethod setterPrototype = PropertyUtil.generateSetterPrototype(field);
                final PsiMethod setter = topLevelClass.findMethodBySignature(setterPrototype, true);

                final String fieldName = field.getName();
                boolean isFinal = false;
                final PsiModifierList modifierList = field.getModifierList();
                if (modifierList != null) {
                    isFinal = modifierList.hasModifierProperty(PsiModifier.FINAL);
                }

                final String assignText;
                if (setter == null || isFinal) {
                    assignText = String.format("%1$s = builder.%1$s;", fieldName);
                } else {
                    assignText = String.format("%s(builder.%s);", setter.getName(), fieldName);
                }

                final PsiStatement assignStatement = psiElementFactory.createStatementFromText(assignText, null);
                constructorBody.add(assignStatement);
            }

            for (final PsiFieldMember member : optionalFields) {
                final PsiField field = member.getElement();

                final PsiMethod setterPrototype = PropertyUtil.generateSetterPrototype(field);
                final PsiMethod setter = topLevelClass.findMethodBySignature(setterPrototype, true);

                final String fieldName = field.getName();
                boolean isFinal = false;
                final PsiModifierList modifierList = field.getModifierList();
                if (modifierList != null) {
                    isFinal = modifierList.hasModifierProperty(PsiModifier.FINAL);
                }

                final String assignText;
                if (setter == null || isFinal) {
                    assignText = String.format("%1$s = builder.%1$s;", fieldName);
                } else {
                    assignText = String.format("%s(builder.%s);", setter.getName(), fieldName);
                }

                final PsiStatement assignStatement = psiElementFactory.createStatementFromText(assignText, null);
                constructorBody.add(assignStatement);
            }
        }

        return constructor;
    }

    private PsiMethod generateBuildMethod(final PsiClass topLevelClass, final Set<StepBuilderOption> options) {
        final PsiType topLevelClassType = psiElementFactory.createType(topLevelClass);
        final PsiMethod buildMethod = psiElementFactory.createMethod("build", topLevelClassType);

        buildMethod.getModifierList().setModifierProperty(PsiModifier.PUBLIC, true);

        final PsiCodeBlock buildMethodBody = buildMethod.getBody();
        if (buildMethodBody != null) {
            final PsiStatement returnStatement = psiElementFactory.createStatementFromText(String.format(
                    "return new %s(this);", topLevelClass.getName()), buildMethod);
            buildMethodBody.add(returnStatement);
        }
        setBuildMethodComment(buildMethod, topLevelClass);
        return buildMethod;
    }

    @NotNull
    private PsiClass findOrCreateBuilderClass(final PsiClass topLevelClass, Collection<PsiClassType> interfaces, PsiClassType optionalInterface) {
        final PsiClass builderClass = topLevelClass.findInnerClassByName(BUILDER_CLASS_NAME, false);
        if (builderClass == null) {
            List<PsiClassType> types = new ArrayList<>(interfaces);
            types.add(optionalInterface);

            return createBuilderClass(topLevelClass, types);
        }

        return builderClass;
    }

    @NotNull
    private PsiClass createBuilderClass(final PsiClass topLevelClass, List<PsiClassType> implementedTypes) {
        final PsiClass builderClass = (PsiClass) topLevelClass.add(psiElementFactory.createClass(BUILDER_CLASS_NAME));
        PsiUtil.setModifierProperty(builderClass, PsiModifier.STATIC, true);
        PsiUtil.setModifierProperty(builderClass, PsiModifier.FINAL, true);

        if(builderClass.getImplementsList() != null) {
            for (PsiClassType type : implementedTypes) {
                builderClass.getImplementsList().add(psiElementFactory.createReferenceElementByType(type));
            }
        }

        setBuilderComment(builderClass, topLevelClass);
        return builderClass;
    }

    private PsiElement findOrCreateField(final PsiClass builderClass, final PsiFieldMember member,
                                         @Nullable final PsiElement last) {
        final PsiField field = member.getElement();
        final String fieldName = field.getName();
        final PsiType fieldType = field.getType();
        final PsiField existingField = builderClass.findFieldByName(fieldName, false);
        if (existingField == null || !StepBuilderUtils.areTypesPresentableEqual(existingField.getType(), fieldType)) {
            if (existingField != null) {
                existingField.delete();
            }
            final PsiField newField = psiElementFactory.createField(fieldName, fieldType);
            if (last != null) {
                return builderClass.addAfter(newField, last);
            } else {
                return builderClass.add(newField);
            }
        }
        return existingField;
    }

    private PsiElement addMethod(@NotNull final PsiClass target, @Nullable final PsiElement after,
                                 @NotNull final PsiMethod newMethod, final boolean replace) {
        PsiMethod existingMethod = target.findMethodBySignature(newMethod, false);
        if (existingMethod == null && newMethod.isConstructor()) {
            for (final PsiMethod constructor : target.getConstructors()) {
                if (StepBuilderUtils.areParameterListsEqual(constructor.getParameterList(),
                        newMethod.getParameterList())) {
                    existingMethod = constructor;
                    break;
                }
            }
        }
        if (existingMethod == null) {
            if (after != null) {
                return target.addAfter(newMethod, after);
            } else {
                return target.add(newMethod);
            }
        } else if (replace) {
            existingMethod.replace(newMethod);
        }
        return existingMethod;
    }

    private void setBuilderComment(final PsiClass clazz, final PsiClass topLevelClass) {
        if (currentOptions().contains(StepBuilderOption.WITH_JAVADOC)) {
            StringBuilder str = new StringBuilder("/**\n").append("* {@code ");
            str.append(topLevelClass.getName()).append("} builder static inner class.\n");
            str.append("*/");
            setStringComment(clazz, str.toString());
        }
    }

    private void setSetterComment(final PsiMethod method, final String fieldName, final String parameterName, final PsiType returnType) {
        if (currentOptions().contains(StepBuilderOption.WITH_JAVADOC)) {
            StringBuilder str = new StringBuilder("/**\n").append("* Sets the {@code ").append(fieldName);
            str.append("} and returns a reference to {@code ").append(returnType.getPresentableText());
            str.append("}\n* @param ").append(parameterName).append(" the {@code ");
            str.append(fieldName).append("} to set\n");
            str.append("* @return a reference to this Builder\n*/");
            setStringComment(method, str.toString());
        }
    }

    private void setBuildMethodComment(final PsiMethod method, final PsiClass topLevelClass) {
        if (currentOptions().contains(StepBuilderOption.WITH_JAVADOC)) {
            StringBuilder str = new StringBuilder("/**\n");
            str.append("* Returns a {@code ").append(topLevelClass.getName()).append("} built ");
            str.append("from the parameters previously set.\n*\n");
            str.append("* @return a {@code ").append(topLevelClass.getName()).append("} ");
            str.append("built with parameters of this {@code ").append(topLevelClass.getName()).append(".Builder}\n*/");
            setStringComment(method, str.toString());
        }
    }

    private void setStringComment(final PsiMethod method, final String strComment) {
        PsiComment comment = psiElementFactory.createCommentFromText(strComment, null);
        PsiDocComment doc = method.getDocComment();
        if (doc != null) {
            doc.replace(comment);
        } else {
            method.addBefore(comment, method.getFirstChild());
        }
    }

    private void setStringComment(final PsiClass clazz, final String strComment) {
        PsiComment comment = psiElementFactory.createCommentFromText(strComment, null);
        PsiDocComment doc = clazz.getDocComment();
        if (doc != null) {
            doc.replace(comment);
        } else {
            clazz.addBefore(comment, clazz.getFirstChild());
        }
    }
}
