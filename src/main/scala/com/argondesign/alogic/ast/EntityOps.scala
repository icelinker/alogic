////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2019 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Geza Lore
//
// DESCRIPTION:
//
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.ast

import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.core.Symbols.TypeSymbol
import com.argondesign.alogic.core.Types.TypeEntity
import com.argondesign.alogic.util.unreachable

trait EntityOps { this: Entity =>
  lazy val declarations = this.body collect { case EntDecl(decl: Declaration) => decl }

  lazy val entities = this.body collect { case EntEntity(entity) => entity }

  lazy val instances = this.body collect { case node: EntInstance => node }

  lazy val connects = this.body collect { case node: EntConnect => node }

  lazy val functions = this.body collect { case node: EntFunction => node }

  lazy val states = this.body collect { case node: EntState => node }

  lazy val combProcesses = this.body collect { case node: EntCombProcess => node }

  lazy val verbatims = this.body collect { case node: EntVerbatim => node }

  lazy val symbol = this.ref match {
    case Sym(symbol: TypeSymbol, Nil) => symbol
    case _                            => unreachable
  }

  lazy val name = this.ref match {
    case Ident(n, _) => n
    case Sym(s, _)   => s.name
  }

  // Get the type of the entity based on contained parameter and port declarations
  def typeBasedOnContents: TypeEntity = {
    val paramSymbols = declarations collect {
      case Decl(s, _) if s.kind.isParam => s
    }

    val portSymbols = declarations collect {
      case Decl(s, _) if s.kind.isIn  => s
      case Decl(s, _) if s.kind.isOut => s
    }

    TypeEntity(name, portSymbols, paramSymbols)
  }
}
