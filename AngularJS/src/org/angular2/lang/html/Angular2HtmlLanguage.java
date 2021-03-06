// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.angular2.lang.html;

import com.intellij.lang.html.HTMLLanguage;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.Nullable;

public class Angular2HtmlLanguage extends XMLLanguage {
  public static final Angular2HtmlLanguage INSTANCE = new Angular2HtmlLanguage();

  protected Angular2HtmlLanguage() {
    super(HTMLLanguage.INSTANCE, "Angular2Html");
  }

  @Nullable
  @Override
  public LanguageFileType getAssociatedFileType() {
    return Angular2HtmlFileType.NG_FILE_TYPE;
  }
}
