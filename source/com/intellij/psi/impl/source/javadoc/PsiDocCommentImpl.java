package com.intellij.psi.impl.source.javadoc;

import com.intellij.lexer.JavaDocLexer;
import com.intellij.lexer.JavaLexer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.impl.source.parsing.ChameleonTransforming;
import com.intellij.psi.impl.source.tree.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.javadoc.PsiDocTag;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.CharTable;
import com.intellij.util.text.CharArrayCharSequence;
import com.intellij.lang.ASTNode;

import java.util.ArrayList;
import java.util.regex.Pattern;

public class PsiDocCommentImpl extends CompositePsiElement implements PsiDocComment, JavaTokenType, Reparseable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.source.javadoc.PsiDocCommentImpl");

  private static final TokenSet TAG_BIT_SET = TokenSet.create(new IElementType[]{DOC_TAG});
  private static final PsiElementArrayConstructor PSI_TAG_ARRAY_CONSTRUCTOR = new PsiElementArrayConstructor() {
    public PsiElement[] newPsiElementArray(int length) {
      return length != 0 ? new PsiDocTag[length] : PsiDocTag.EMPTY_ARRAY;
    }
  };
  public static final Pattern WS_PATTERN = Pattern.compile("\\s*");


  public PsiDocCommentImpl() {
    super(DOC_COMMENT);
  }

  public PsiElement[] getDescriptionElements() {
    ChameleonTransforming.transformChildren(this);
    ArrayList<ASTNode> array = new ArrayList<ASTNode>();
    for (ASTNode child = getFirstChildNode(); child != null; child = child.getTreeNext()) {
      IElementType i = child.getElementType();
      if (i == DOC_TAG) break;
      if (i != DOC_COMMENT_START && i != DOC_COMMENT_END && i != DOC_COMMENT_LEADING_ASTERISKS) {
        array.add(child);
      }
    }
    return array.toArray(new PsiElement[array.size()]);
  }

  public PsiDocTag[] getTags() {
    return (PsiDocTag[])getChildrenAsPsiElements(TAG_BIT_SET, PSI_TAG_ARRAY_CONSTRUCTOR);
  }

  public PsiDocTag findTagByName(String name) {
    if (getFirstChildNode().getElementType() == DOC_COMMENT_TEXT) {
      if (getFirstChildNode().getText().indexOf(name) < 0) return null;
    }

    char[] tagChars = new char[name.length() + 1];
    tagChars[0] = '@';
    name.getChars(0, name.length(), tagChars, 1);

    ChameleonTransforming.transformChildren(this);
    for (ASTNode child = getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (child.getElementType() == DOC_TAG) {
        PsiDocTag tag = (PsiDocTag)SourceTreeToPsiMap.treeElementToPsi(child);
        if (tag.getNameElement().textMatches(new CharArrayCharSequence(tagChars))) {
          return tag;
        }
      }
    }

    return null;
  }

  public PsiDocTag[] findTagsByName(String name) {
    ArrayList<PsiDocTag> array = new ArrayList<PsiDocTag>();
    PsiDocTag[] tags = getTags();
    name = "@" + name;
    for (int i = 0; i < tags.length; i++) {
      PsiDocTag tag = tags[i];
      if (tag.getNameElement().getText().equals(name)) {
        array.add(tag);
      }
    }
    return array.toArray(new PsiDocTag[array.size()]);
  }

  public IElementType getTokenType() {
    return getElementType();
  }

  public ASTNode findChildByRole(int role) {
    LOG.assertTrue(ChildRole.isUnique(role));
    ChameleonTransforming.transformChildren(this);
    switch (role) {
      default:
        return null;

      case ChildRole.DOC_COMMENT_START:
        return getFirstChildNode();

      case ChildRole.DOC_COMMENT_END:
        if (getLastChildNode().getElementType() == DOC_COMMENT_END) {
          return getLastChildNode();
        }
        else {
          return null;
        }
    }
  }

  private static boolean isWhitespaceCommentData(ASTNode docCommentData) {
    return WS_PATTERN.matcher(docCommentData.getText()).matches();
  }

  private static void addNewLineToTag(CompositeElement tag) {
    LOG.assertTrue(tag != null && tag.getElementType() == DOC_TAG);
    ASTNode current = tag.getLastChildNode();
    while (current != null && current.getElementType() == DOC_COMMENT_DATA && isWhitespaceCommentData(current)) {
      current = current.getTreePrev();
    }
    if (current != null && current.getElementType() == DOC_COMMENT_LEADING_ASTERISKS) return;
    final CharTable treeCharTab = SharedImplUtil.findCharTableByTree(tag);
    final ASTNode newLine = Factory.createSingleLeafElement(/*DOC_COMMENT_DATA*/WHITE_SPACE, new char[]{'\n'}, 0, 1, treeCharTab, null);
    tag.addChild(newLine, null);
    final TreeElement leadingAsterisk = Factory.createSingleLeafElement(DOC_COMMENT_LEADING_ASTERISKS, new char[]{'*'}, 0, 1, treeCharTab,
                                                                        null);
    tag.addInternal(leadingAsterisk, leadingAsterisk, null, Boolean.TRUE);
    final TreeElement commentData = Factory.createSingleLeafElement(DOC_COMMENT_DATA, new char[]{' '}, 0, 1, treeCharTab, null);
    tag.addInternal(commentData, commentData, null, Boolean.TRUE);
  }

  public TreeElement addInternal(TreeElement first, ASTNode last, ASTNode anchor, Boolean before) {
    TreeElement firstAdded = null;
    boolean needToAddNewline = false;
    if (first == last && first.getElementType() == DOC_TAG) {
      if (anchor == null) {
        anchor = getLastChildNode(); // this is a '*/'
        final ASTNode prevBeforeWS = TreeUtil.skipElementsBack(anchor.getTreePrev(), WHITE_SPACE_BIT_SET);
        if (prevBeforeWS != null) {
          anchor = prevBeforeWS;
          before = Boolean.FALSE;
        }
        else {
          before = Boolean.TRUE;
        }
        needToAddNewline = true;
      }
      if (!(anchor.getElementType() == DOC_TAG)) {
        final CharTable charTable = SharedImplUtil.findCharTableByTree(this);
        final TreeElement newLine = Factory.createSingleLeafElement(DOC_COMMENT_DATA, new char[]{'\n'}, 0, 1, charTable, null);
        firstAdded = super.addInternal(newLine, newLine, anchor, before);
        final TreeElement leadingAsterisk = Factory.createSingleLeafElement(DOC_COMMENT_LEADING_ASTERISKS, new char[]{'*'}, 0, 1,
                                                                            charTable, null);
        super.addInternal(leadingAsterisk, leadingAsterisk, newLine, Boolean.FALSE);
        final TreeElement commentData = Factory.createSingleLeafElement(DOC_COMMENT_DATA, new char[]{' '}, 0, 1, charTable, null);
        super.addInternal(commentData, commentData, leadingAsterisk, Boolean.FALSE);
        anchor = commentData;
        before = Boolean.FALSE;
      }
      else {
        needToAddNewline = true;
      }
    }

    if (firstAdded != null) {
      firstAdded = super.addInternal(first, last, anchor, before);
    }
    else {
      super.addInternal(first, last, anchor, before);
    }
    if (needToAddNewline) {
      if (first.getTreePrev() != null && first.getTreePrev().getElementType() == DOC_TAG) {
        addNewLineToTag((CompositeElement)first.getTreePrev());
      }
      if (first.getTreeNext() != null && first.getTreeNext().getElementType() == DOC_TAG) {
        addNewLineToTag((CompositeElement)first);
      }
      else {
        removeEndingAsterisksFromTag((CompositeElement)first);
      }
    }
    return firstAdded;
  }

  private static void removeEndingAsterisksFromTag(CompositeElement tag) {
    ASTNode current = tag.getLastChildNode();
    while (current != null && current.getElementType() == DOC_COMMENT_DATA) {
      current = current.getTreePrev();
    }
    if (current != null && current.getElementType() == DOC_COMMENT_LEADING_ASTERISKS) {
      final ASTNode prevWhiteSpace = TreeUtil.skipElementsBack(current.getTreePrev(), WHITE_SPACE_BIT_SET);
      ASTNode toBeDeleted = prevWhiteSpace.getTreeNext();
      while (toBeDeleted != null) {
        ASTNode next = toBeDeleted.getTreeNext();
        tag.deleteChildInternal(toBeDeleted);
        toBeDeleted = next;
      }
    }
  }

  public void deleteChildInternal(ASTNode child) {
    if (child.getElementType() == DOC_TAG) {
      if (child.getTreeNext() == null || child.getTreeNext().getElementType() != DOC_TAG) {
        ASTNode prev = child.getTreePrev();
        while (prev != null && prev.getElementType() == DOC_COMMENT_DATA) {
          prev = prev.getTreePrev();
        }
        if (prev != null && prev.getElementType() == DOC_COMMENT_LEADING_ASTERISKS) {
          ASTNode leadingAsterisk = prev;
          if (leadingAsterisk.getTreePrev() != null) {
            super.deleteChildInternal(leadingAsterisk.getTreePrev());
            super.deleteChildInternal(leadingAsterisk);
          }
        }
        else if (prev != null && prev.getElementType() == DOC_TAG) {
          final CompositeElement compositePrev = (CompositeElement)prev;
          final ASTNode lastPrevChild = compositePrev.getLastChildNode();
          ASTNode prevChild = lastPrevChild;
          while (prevChild != null && prevChild.getElementType() == DOC_COMMENT_DATA) {
            prevChild = prevChild.getTreePrev();
          }
          if (prevChild != null && prevChild.getElementType() == DOC_COMMENT_LEADING_ASTERISKS) {
            ASTNode current = prevChild;
            while (current != null) {
              final ASTNode next = current.getTreeNext();
              compositePrev.deleteChildInternal(current);
              current = next;
            }
          }
        }
      }

    }
    super.deleteChildInternal(child);
  }

  public int getChildRole(ASTNode child) {
    LOG.assertTrue(child.getTreeParent() == this);
    IElementType i = child.getElementType();
    if (i == DOC_TAG) {
      return ChildRole.DOC_TAG;
    }
    else if (i == DOC_COMMENT_TEXT || i == DOC_INLINE_TAG) {
      return ChildRole.DOC_CONTENT;
    }
    else if (i == DOC_COMMENT_LEADING_ASTERISKS) {
      return ChildRole.DOC_COMMENT_ASTERISKS;
    }
    else if (i == DOC_COMMENT_START) {
      return ChildRole.DOC_COMMENT_START;
    }
    else if (i == DOC_COMMENT_END) {
      return ChildRole.DOC_COMMENT_END;
    }
    else {
      return ChildRole.NONE;
    }
  }

  public void accept(PsiElementVisitor visitor) {
    visitor.visitDocComment(this);
  }

  public String toString() {
    return "PsiDocComment";
  }

  public ChameleonElement createChameleon(char[] buffer, int start, int end) {
    return new DocCommentChameleonElement(buffer, start, end, -1, SharedImplUtil.findCharTableByTree(this));
  }

  public int getErrorsCount(char[] buffer, int start, int end, int lengthShift) {
    final JavaLexer lexer = new JavaLexer(LanguageLevel.JDK_1_5);
    lexer.start(buffer, start, end);
    if(lexer.getTokenType() != DOC_COMMENT_TEXT) return -1;
    lexer.advance();
    if(lexer.getTokenType() != null) return -1;
    return 0;
  }

  public Class getLexerClass() {
    return JavaDocLexer.class;
  }
}