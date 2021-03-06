package com.intellij.flex.uiDesigner.mxml;

import com.intellij.flex.uiDesigner.FlashUIDesignerBundle;
import com.intellij.flex.uiDesigner.InjectionUtil;
import com.intellij.flex.uiDesigner.InvalidPropertyException;
import com.intellij.flex.uiDesigner.ProblemsHolder;
import com.intellij.javascript.flex.FlexAnnotationNames;
import com.intellij.lang.javascript.JSTokenTypes;
import com.intellij.lang.javascript.psi.*;
import com.intellij.lang.javascript.psi.ecmal4.JSAttribute;
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeNameValuePair;
import com.intellij.lang.javascript.psi.ecmal4.JSClass;
import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiLanguageInjectionHost.Shred;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.intellij.flex.uiDesigner.mxml.MxmlWriter.LOG;

class InjectedPsiVisitor implements PsiLanguageInjectionHost.InjectedPsiVisitor {
  private final PsiElement host;

  private final @Nullable String expectedType;

  private boolean visited;

  private boolean unsupported;
  private ValueWriter valueWriter;
  private InvalidPropertyException invalidPropertyException;
  private Binding binding;

  private final ProblemsHolder problemsHolder;

  InjectedPsiVisitor(PsiElement host, @Nullable String expectedType, ProblemsHolder problemsHolder) {
    this.host = host;
    this.expectedType = expectedType;
    this.problemsHolder = problemsHolder;
  }

  public ValueWriter getValueWriter() {
    return unsupported ? InjectedASWriter.IGNORE : valueWriter;
  }

  public Binding getBinding() {
    return binding;
  }

  public InvalidPropertyException getInvalidPropertyException() {
    return invalidPropertyException;
  }

  @Override
  public void visit(@NotNull PsiFile injectedPsi, @NotNull List<Shred> places) {
    // todo <s:Label text="{demandSelectedTO.journalist.nom} {demandSelectedTO.journalist.prenom}"/>
    // will be called 2 - first for {demandSelectedTO.journalist.nom} and second for {demandSelectedTO.journalist.prenom}
    if (visited) {
      return;
    }

    visited = true;

    assert places.size() == 1;
    assert places.get(0).getHost() == host;
    JSFile jsFile = (JSFile)injectedPsi;
    JSSourceElement[] statements = jsFile.getStatements();
    if (statements.length == 0 && (valueWriter = checkCompilerDirective(jsFile)) != null) {
      return;
    }

    assert statements.length == 1;
    final JSExpression expr = ((JSExpressionStatement)statements[0]).getExpression();
    // <s:Image source="@Embed(source='/Users/develar/Pictures/iChat Ico
    if (!(expr instanceof JSCallExpression)) {
      valueWriter = InjectedASWriter.IGNORE;
      return;
    }

    JSCallExpression expression = (JSCallExpression)expr;
    JSExpression[] arguments = expression.getArguments();
    if (arguments.length != 1) {
      logUnsupported();
      return;
    }

    if (arguments[0] instanceof JSArrayLiteralExpression) {
      if (isUnexpected(JSCommonTypeNames.ARRAY_CLASS_NAME)) {
        return;
      }

      JSArrayLiteralExpression arrayLiteralExpression = (JSArrayLiteralExpression)arguments[0];
      JSExpression[] expressions = arrayLiteralExpression.getExpressions();
      if (expressions.length == 0) {
        valueWriter = InjectedASWriter.IGNORE;
        return;
      }

      // create ExpressionBinding only if expressions contains non-primitive values
      for (JSExpression itemExpression : expressions) {
        if (!(itemExpression instanceof JSLiteralExpression)) {
          binding = new ExpressionBinding(arrayLiteralExpression);
          return;
        }
      }

      valueWriter = new InjectedArrayOfPrimitivesWriter(arrayLiteralExpression);
    }
    else if (arguments[0] instanceof JSReferenceExpression && arguments[0].getChildren().length == 0) {
      // if propertyName="{CustomSkin}", so, write class, otherwise, it is binding
      JSReferenceExpression referenceExpression = (JSReferenceExpression)arguments[0];
      PsiElement element = referenceExpression.resolve();
      if (element == null) {
       invalidPropertyException = new InvalidPropertyException(expression, "unresolved.variable.or.type",
                                                                referenceExpression.getReferencedName());
      }
      else if (element instanceof JSClass) {
        if (isExpectedObjectOrAnyType() || AsCommonTypeNames.CLASS.equals(expectedType)) {
          valueWriter = new ClassValueWriter(((JSClass)element));
        }
        else {
          invalidPropertyException = new InvalidPropertyException(expression, "implicit.coercion", "Class", expectedType);
        }
      }
      else if (element instanceof JSVariable) {
        binding = new VariableBinding(((JSVariable)element));
      }
      else if (element instanceof JSFunction) {
        binding = new ExpressionBinding(((JSFunction)element));
      }
      else {
        binding = new MxmlObjectBinding(referenceExpression.getReferencedName(), JSCommonTypeNames.ARRAY_CLASS_NAME.equals(expectedType));
      }
    }
    else {
      binding = new ExpressionBinding(arguments[0]);
    }
  }

  private ValueWriter checkCompilerDirective(JSFile jsFile) {
    PsiElement firstChild = jsFile.getFirstChild();
    if (firstChild instanceof LeafPsiElement && ((LeafPsiElement)firstChild).getElementType() == JSTokenTypes.AT) {
      JSAttribute attribute = (JSAttribute)firstChild.getNextSibling();
      assert attribute != null;
      final String attributeName = attribute.getName();
      if (FlexAnnotationNames.EMBED.equals(attributeName)) {
        return processEmbedDirective(attribute);
      }
      else if (FlexAnnotationNames.RESOURCE.equals(attributeName)) {
        return processResourceDirective(attribute);
      }
      else {
        if (!StringUtil.isEmpty(attributeName)) {
          problemsHolder.add(host, FlashUIDesignerBundle.message("unsupported.compiler.directive", host.getText()));
        }
        return InjectedASWriter.IGNORE;
      }
    }

    return null;
  }

  private ValueWriter processResourceDirective(JSAttribute attribute) {
    String key = null;
    PropertiesFile bundle = null;
    for (JSAttributeNameValuePair p : attribute.getValues()) {
      final String name = p.getName();
      if ("key".equals(name)) {
        key = p.getSimpleValue();
      }
      else if ("bundle".equals(name)) {
        try {
          // IDEA-74868
          final PsiFileSystemItem referencedPsiFile = InjectionUtil.getReferencedPsiFile(p);
          if (referencedPsiFile instanceof PropertiesFile) {
            bundle = (PropertiesFile)referencedPsiFile;
          }
          else {
            LOG.warn("skip resource directive, referenced file is not properties file " + host.getText());
          }
        }
        catch (InvalidPropertyException e) {
          invalidPropertyException = e;
          return InjectedASWriter.IGNORE;
        }
      }
    }

    if (key == null || key.isEmpty() || bundle == null) {
      LOG.warn("skip resource directive, one of the required attributes is missed " + host.getText());
      return InjectedASWriter.IGNORE;
    }

    final IProperty property = bundle.findPropertyByKey(key);
    if (property == null) {
      LOG.warn("skip resource directive, key not found " + host.getText());
      return InjectedASWriter.IGNORE;
    }

    return new ResourceDirectiveValueWriter(property.getUnescapedValue());
  }

  private ValueWriter processEmbedDirective(JSAttribute attribute) {
    VirtualFile source = null;
    String mimeType = null;
    String symbol = null;
    for (JSAttributeNameValuePair p : attribute.getValues()) {
      final String name = p.getName();
      if (name == null || name.equals("source")) {
        try {
          source = InjectionUtil.getReferencedFile(p);
        }
        catch (InvalidPropertyException e) {
          problemsHolder.add(e);
          return InjectedASWriter.IGNORE;
        }
      }
      else if (name.equals("mimeType")) {
        mimeType = p.getSimpleValue();
      }
      else if (name.equals("symbol")) {
        symbol = p.getSimpleValue();
      }
    }

    if (source == null) {
      problemsHolder.add(host, FlashUIDesignerBundle.message("embed.source.not.specified", host.getText()));
      return InjectedASWriter.IGNORE;
    }

    if (InjectionUtil.isSwf(source, mimeType)) {
      return new SwfValueWriter(source, symbol);
    }
    else {
      if (symbol != null) {
        LOG.warn("Attribute symbol is unneeded for " + host.getText());
      }

      if (InjectionUtil.isImage(source, mimeType)) {
        return new ImageValueWriter(source, mimeType);
      }
      else {
        problemsHolder.add(host, FlashUIDesignerBundle.message("unsupported.embed.asset.type", host.getText()));
        return InjectedASWriter.IGNORE;
      }
    }
  }

  private boolean isUnexpected(String actualType) {
    if (actualType.equals(expectedType) || expectedType == null || isExpectedObjectOrAnyType()) {
      return false;
    }
    else {
      problemsHolder.add(host, "Expected " + expectedType + ", but got " + host.getText());
      unsupported = true;
      return true;
    }
  }

  private boolean isExpectedObjectOrAnyType() {
    return JSCommonTypeNames.OBJECT_CLASS_NAME.equals(expectedType) || JSCommonTypeNames.ANY_TYPE.equals(expectedType);
  }

  private void logUnsupported() {
    LOG.warn("unsupported injected AS: " + host.getText());
    unsupported = true;
  }
}
