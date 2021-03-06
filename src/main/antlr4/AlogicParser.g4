
////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2017-2018 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Peter de Rivaz/Geza Lore
//
// DESCRIPTION:
//
// Antlr4 parser grammar for Alogic
////////////////////////////////////////////////////////////////////////////////

parser grammar AlogicParser;

options {
  tokenVocab = AlogicLexer;
}

///////////////////////////////////////////////////////////////////////////////
// Start rule for whole source file
///////////////////////////////////////////////////////////////////////////////

start
  : defn*
    entity
    EOF
  ;

///////////////////////////////////////////////////////////////////////////////
// Type names
///////////////////////////////////////////////////////////////////////////////

kind
  : kind ('[' expr ']')+ # TypeVec
  | 'bool'               # TypeBool
  | INTTYPE              # TypeInt
  | UINTTYPE             # TypeUInt
  | 'int'  '(' expr ')'  # TypeIntN
  | 'uint' '(' expr ')'  # TypeUIntN
  | 'int'                # TypeSNum
  | 'uint'               # TypeUNum
  | ident                # TypeIdent
  | 'void'               # TypeVoid
  ;

///////////////////////////////////////////////////////////////////////////////
// Identifiers
///////////////////////////////////////////////////////////////////////////////

ident : IDENTIFIER ('#' '[' expr (',' expr)* ']')? ;

///////////////////////////////////////////////////////////////////////////////
// Definitions
///////////////////////////////////////////////////////////////////////////////

defn
  : 'typedef' kind ident ';'   # DefnTypedef
  | 'struct' ident '{'
      field+
    '}'                        # DefnStruct
  ;

field: kind IDENTIFIER SEMICOLON;

///////////////////////////////////////////////////////////////////////////////
// Declarations
///////////////////////////////////////////////////////////////////////////////

decl: attr? declbase ('=' expr)? ';' ;

declbase
  : kind ident                                         # DeclVar
  | 'out' flow_control_type? storage_type? kind ident  # DeclOut
  | 'in' flow_control_type? kind ident                 # DeclIn
  | 'param' kind IDENTIFIER                            # DeclParam
  | 'const' kind IDENTIFIER                            # DeclConst
  | 'pipeline' kind ident                              # DeclPipeline
  | kind ident '[' expr ']'                            # DeclArr
  | 'sram' (wire='wire')? kind ident '[' expr ']'      # DeclSram
  ;

flow_control_type
  : 'sync'        # FlowControlTypeSync
  | SYNC_READY    # FlowControlTypeSyncReady
  | SYNC_ACCEPT   # FlowControlTypeSyncAccept
  ;

storage_type
  : 'wire'                                      # StorageTypeWire
  | (slices+=('bubble' | 'fslice' | 'bslice'))+ # StorageTypeSlices
  ;

///////////////////////////////////////////////////////////////////////////////
// Entity
///////////////////////////////////////////////////////////////////////////////

entity
  : attr?
    (variant='fsm' | variant='network' | variant='verbatim' 'entity') ident '{'
      (ent)*
    '}'
  ;

///////////////////////////////////////////////////////////////////////////////
// Entity contents
///////////////////////////////////////////////////////////////////////////////

ent
  : decl                                                          # EntDecl
  | defn                                                          # EntDefn
  | (attr? autoinst='new')? entity                                # EntEntity
  | attr? ident eqsign='=' 'new' ident '(' param_assigns ')' ';'  # EntInstance
  | lhs=expr '->' rhs+=expr (',' rhs+=expr)* ';'                  # EntConnect
  | 'fence' block                                                 # EntFenceBlock
  | attr? 'void' ident '(' ')' block                              # EntFunction
  | 'verbatim' IDENTIFIER VERBATIM_BODY                           # EntVerbatimBlock
  | generate                                                      # EntGen
  ;

param_assigns : (IDENTIFIER '=' expr (','  IDENTIFIER '=' expr)*)? ;

///////////////////////////////////////////////////////////////////////////////
// Gen constructs
///////////////////////////////////////////////////////////////////////////////

generate : 'gen' gen ;

gen
  : 'if' '(' thenCond=expr ')' '{' thenItems=genitems '}'
    ('else' 'if' '(' elifCond+=expr ')' '{' elifItems+=genitems '}')*
    ('else' '{' elseItems=genitems '}')?                                    # GenIf
  | 'for' '(' loop_init? ';' expr? ';' for_steps? ')' '{' genitems '}'      # GenFor
  | 'for' '(' kind IDENTIFIER op=('<' | '<=')  expr ')' '{' genitems '}'    # GenRange
  ;

genitems : genitem* ;

genitem
  : generate        # GenItemGen
  | decl            # GenItemDecl
  | defn            # GenItemDefn
  | statement       # GenItemStmt
  | case_clause     # GenItemCase
  | ent             # GenItemEnt
  ;

///////////////////////////////////////////////////////////////////////////////
// Statements
///////////////////////////////////////////////////////////////////////////////

block
  : '{' statement* '}'
  ;

statement
  : block                                                               # StmtBlock
  | 'if' '(' expr ')' thenStmt=statement ('else' elseStmt=statement)?   # StmtIf
  | 'case' '(' expr ')' '{' case_clause+ '}'                            # StmtCase
  | let loop                                                            # StmtLet
  | loop                                                                # StatementLoop
  | 'goto' ident ';'                                                    # StmtGoto
  | 'fence' ';'                                                         # StmtFence
  | 'break' ';'                                                         # StmtBreak
  | 'continue' ';'                                                      # StmtContinue
  | 'return' ';'                                                        # StmtReturn
  | decl                                                                # StmtDecl
  | assignment ';'                                                      # StatementAssignment
  | expr ';'                                                            # StmtExpr
  | generate                                                            # StmtGen
  ;

loop
  : 'loop' block                                                   # StmtLoop
  | 'do' block 'while' '(' expr ')' ';'                            # StmtDo
  | 'while' '(' expr ')' block                                     # StmtWhile
  | 'for' '(' loop_init?  ';' expr? ';' for_steps? ')' block       # StmtFor
  ;

let
  : 'let' '(' loop_init ')'
  ;

case_clause
  : commaexpr ':' statement # CaseRegular
  | 'default' ':' statement # CaseDefault
  | generate                # CaseGen
  ;

assignment
  : expr op='=' expr    # StmtAssign
  | expr ASSIGNOP expr  # StmtUpdate
  | expr op=('++'|'--') # StmtPost
  ;

loop_init
  : loop_init_item (',' loop_init_item)*
  ;

loop_init_item
  : expr '=' expr             # LoopInitAssign
  | kind IDENTIFIER '=' expr  # LoopInitDecl
  ;

for_steps
  : step+=assignment (',' step+=assignment)*
  ;

///////////////////////////////////////////////////////////////////////////////
// Expressions
///////////////////////////////////////////////////////////////////////////////

expr
  : '(' expr ')'                                                # ExprBracket
  // Literals
  | 'true'                                                      # ExprTrue
  | 'false'                                                     # ExprFalse
  | sign=('+' | '-')? SIZEDINT                                  # ExprSizedInt
  | sign=('+' | '-')? UNSIZEDINT                                # ExprUnsizedInt
  | STRING                                                      # ExprString
  // Names
  | ident                                                       # ExprIdent
  | ATID                                                        # ExprAtid
  | DOLLARID                                                    # ExprDollarid
  // Call
  | expr '(' commaexpr? ')'                                     # ExprCall
  // Index/Slice
  | expr '[' idx=expr ']'                                       # ExprIndex
  | expr '[' lidx=expr op=(':' | '-:' | '+:') ridx=expr ']'     # ExprSlice
  // Select
  | expr '.' ident                                              # ExprSelect
  // Operators
  | op=('+' | '-' | '~' | '!' | '&' | '|' | '^' | '\'' ) expr   # ExprUnary
  | expr op=('*' | '/' | '%') expr                              # ExprBinary
  | expr op=('+' | '-') expr                                    # ExprBinary
  | expr op=('<<' | '>>' | '>>>' | '<<<' ) expr                 # ExprBinary
  | expr op=('>' | '>=' | '<' | '<=') expr                      # ExprBinary
  | expr op=('==' | '!=') expr                                  # ExprBinary
  | expr op='&' expr                                            # ExprBinary
  | expr op='^' expr                                            # ExprBinary
  | expr op='|' expr                                            # ExprBinary
  | expr op='&&' expr                                           # ExprBinary
  | expr op='||' expr                                           # ExprBinary
  |<assoc=right> expr op='?' expr ':' expr                      # ExprTernary
  | '{' expr '{' commaexpr '}' '}'                              # ExprRep
  | '{' commaexpr '}'                                           # ExprCat
  // Type
  | kind                                                        # ExprType
  ;

commaexpr: expr (',' expr)* ;

///////////////////////////////////////////////////////////////////////////////
// Attributes
///////////////////////////////////////////////////////////////////////////////

attr: '(*' attrspec (',' attrspec)* '*)' ;
attrspec: IDENTIFIER ('=' expr)? ;
