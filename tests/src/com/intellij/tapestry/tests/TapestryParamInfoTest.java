package com.intellij.tapestry.tests;

import com.intellij.codeInsight.hint.api.impls.XmlParameterInfoHandler;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext;
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext;
import com.intellij.util.Function;
import com.intellij.xml.XmlAttributeDescriptor;
import com.intellij.xml.XmlElementDescriptor;

/**
 * @author Alexey.Chmutov
 */
public class TapestryParamInfoTest extends TapestryBaseTestCase {
  public void testTmlTagAttrs() throws Exception {
    addComponentToProject("Count");
    doTest("end start value");
  }

  public void testHtmlTagAttrs() throws Exception {
    addComponentToProject("Count");
    doTest("class dir end id lang onclick ondblclick onkeydown onkeypress onkeyup onmousedown onmousemove onmouseout onmouseover onmouseup start style title value");
  }

  private void doTest(String attrs) throws Exception {
    initByComponent();

    XmlParameterInfoHandler handler = new XmlParameterInfoHandler();
    MockCreateParameterInfoContext createContext = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
    XmlTag tag = handler.findElementForParameterInfo(createContext);
    assertNotNull(tag);
    handler.showParameterInfo(tag, createContext);
    Object[] items = createContext.getItemsToShow();
    assertTrue(items != null);
    assertTrue(items.length > 0);
    final XmlElementDescriptor descriptor = (XmlElementDescriptor)items[0];
    MockParameterInfoUIContext context = new MockParameterInfoUIContext<PsiElement>(tag);
    handler.updateUI(descriptor, context);

    String joined = StringUtil.join(handler.getParametersForDocumentation(descriptor, createContext), new Function<Object, String>() {

      public String fun(Object o) {
        return ((XmlAttributeDescriptor)o).getName();
      }
    }, " ");
    assertEquals(joined, attrs);

  }

  protected String getBasePath() {
    return "parameterInfo/";
  }
}