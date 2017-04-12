/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.refactoring.convertToInstanceMethod;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.refactoring.HelpID;
import com.intellij.refactoring.RefactoringActionHandler;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author dsl
 */
public class ConvertToInstanceMethodHandler implements RefactoringActionHandler {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.convertToInstanceMethod.ConvertToInstanceMethodHandler");
  static final String REFACTORING_NAME = RefactoringBundle.message("convert.to.instance.method.title");

  public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
    PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
    editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    if (element == null) {
      element = file.findElementAt(editor.getCaretModel().getOffset());
    }

    if (element == null) return;
    if (element instanceof PsiIdentifier) element = element.getParent();

    if(!(element instanceof PsiMethod)) {
      String message = RefactoringBundle.getCannotRefactorMessage(RefactoringBundle.message("error.wrong.caret.position.method"));
      CommonRefactoringUtil.showErrorHint(project, editor, message, REFACTORING_NAME, HelpID.CONVERT_TO_INSTANCE_METHOD);
      return;
    }
    if(LOG.isDebugEnabled()) {
      LOG.debug("MakeMethodStaticHandler invoked");
    }
    invoke(project, new PsiElement[]{element}, dataContext);
  }

  public void invoke(@NotNull Project project, @NotNull PsiElement[] elements, DataContext dataContext) {
    if (elements.length != 1 || !(elements[0] instanceof PsiMethod)) return;
    final PsiMethod method = (PsiMethod)elements[0];
    if (!method.hasModifierProperty(PsiModifier.STATIC)) {
      String message = RefactoringBundle.message("convertToInstanceMethod.method.is.not.static", method.getName());
      Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
      CommonRefactoringUtil.showErrorHint(project, editor, message, REFACTORING_NAME, HelpID.CONVERT_TO_INSTANCE_METHOD);
      return;
    }
    final PsiParameter[] parameters = method.getParameterList().getParameters();
    List<PsiElement> targetQualifiers = new ArrayList<>();
    for (final PsiParameter parameter : parameters) {
      final PsiType type = parameter.getType();
      if (type instanceof PsiClassType) {
        final PsiClass psiClass = ((PsiClassType)type).resolve();
        if (psiClass != null && !(psiClass instanceof PsiTypeParameter)) {
          if (method.getManager().isInProject(psiClass)) {
            targetQualifiers.add(parameter);
          }
        }
      }
    }
    PsiClass containingClass = method.getContainingClass();
    if (containingClass == null || containingClass.getQualifiedName() == null) return;
    String className = containingClass.getName();
    PsiMethod[] constructors = containingClass.getConstructors();
    boolean noArgConstructor =
      constructors.length == 0 || Arrays.stream(constructors).anyMatch(constructor -> constructor.getParameterList().getParametersCount() == 0);
    if (noArgConstructor) {
      targetQualifiers.add(JavaPsiFacade.getElementFactory(project).createExpressionFromText("new " + className + "()", method));
    }

    new ConvertToInstanceMethodDialog(
      method,
      targetQualifiers.toArray(new PsiElement[targetQualifiers.size()])).show();
  }
}
