////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2018 Argon Design Ltd. All rights reserved.
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// Module: Alogic Compiler
// Author: Geza Lore
//
// DESCRIPTION:
//
// Symbol representation and creation
////////////////////////////////////////////////////////////////////////////////

// A Symbol is a unique handle to the definition of a Name

package com.argondesign.alogic.core

import scala.collection.mutable

import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.util.FollowedBy.any2FollowedByWord
import com.argondesign.alogic.util.unreachable

import Denotations.Denotation
import Denotations.TermDenotation
import Denotations.TypeDenotation
import Names.Name
import Names.TermName
import Names.TypeName
import Symbols.Symbol
import Symbols.TermSymbol
import Symbols.TypeSymbol
import Types._

trait Symbols { self: CompilerContext =>

  // The global scope only holds file level entity symbols
  final private[this] var _globalScope: Option[mutable.HashMap[Name, Symbol]] = Some(
    mutable.HashMap())

  // Can only hand out the final immutable copy
  final lazy val globalScope: Map[Name, Symbol] = {
    _globalScope.get.toMap
  } followedBy {
    _globalScope = None
  }

  // Add a symbol to the global scope, assuming it is still open
  final def addGlobalSymbol(symbol: Symbol): Unit = synchronized {
    _globalScope match {
      case None => ice("Global scope is already sealed")
      case Some(scope) => {
        val name = symbol.denot.name
        if (scope contains name) {
          ice(s"Global scope already contains '${name}'")
        }
        scope(name) = symbol
      }
    }
  }

  final def addGlobalEntities(entities: Iterable[Entity]): Unit = synchronized {
    for (Entity(ident: Ident, _, _, _, _, _, _, _, _) <- entities) {
      val attr = if (ident.hasAttr) ident.attr else Map.empty[String, Expr]
      val kind = TypeEntity("", Nil, Nil)
      val symbol = newTypeSymbolWithAttr(ident.name, ident.loc, kind, attr)
      addGlobalSymbol(symbol)
    }

    // Force value to seal global scope
    globalScope
  }

  final def addGlobalEntity(entity: Entity): Unit = addGlobalEntities(List(entity))

  final def lookupGlobalTerm(name: String): Symbol = synchronized {
    globalScope.get(TermName(name)) match {
      case Some(symbol) => symbol
      case None         => ice(s"Cannot find global term '${name}'")
    }
  }

  // Used to look up builtin symbols
  final def getGlobalTermSymbolRef(name: String): ExprRef = {
    val symbol = lookupGlobalTerm(name)
    val sym = Sym(symbol)
    ExprRef(sym)
  }

  final def getGlobalTermSymbolRef(name: String, loc: Loc): ExprRef = {
    val ref = getGlobalTermSymbolRef(name)
    ref visitAll { case node: Tree => node withLoc loc }
    ref
  }

  final private[this] val symbolSequenceNumbers = Stream.from(0).iterator

  final protected val symbolLocations = mutable.HashMap[Symbol, Loc]()

  //////////////////////////////////////////////////////////////////////////////
  // Creating TermSymbol instances
  //////////////////////////////////////////////////////////////////////////////

  final def newTermSymbolWithAttr(
      name: String,
      loc: Loc,
      kind: Type,
      attr: Map[String, Expr]
  ): TermSymbol = synchronized {
    val symbol = new TermSymbol(symbolSequenceNumbers.next)
    val denot = TermDenotation(symbol, TermName(name), kind, attr)

    symbolLocations(symbol) = loc

    symbol withDenot denot
  }

  final def newTermSymbol(name: String, loc: Loc, kind: Type): TermSymbol = {
    newTermSymbolWithAttr(name, loc, kind, Map.empty)
  }

  final def newTermSymbol(ident: Ident, kind: Type): TermSymbol = {
    newTermSymbol(ident.name, ident.loc, kind)
  }

  final def newSymbolLike(symbol: TermSymbol): TermSymbol = {
    newTermSymbolWithAttr(
      symbol.denot.name.str,
      symbol.loc(this),
      symbol.denot.kind,
      symbol.denot.attr
    )
  }

  //////////////////////////////////////////////////////////////////////////////
  // Creating TypeSymbol instances
  //////////////////////////////////////////////////////////////////////////////

  final def newTypeSymbolWithAttr(
      name: String,
      loc: Loc,
      kind: Type,
      attr: Map[String, Expr]
  ): TypeSymbol = synchronized {
    val symbol = new TypeSymbol(symbolSequenceNumbers.next)
    val denot = TypeDenotation(symbol, TypeName(name), kind, attr)

    symbolLocations(symbol) = loc

    symbol withDenot denot
  }

  final def newTypeSymbol(name: String, loc: Loc, kind: Type): TypeSymbol = {
    newTypeSymbolWithAttr(name, loc, kind, Map.empty)
  }

  final def newTypeSymbol(ident: Ident, kind: Type): TypeSymbol = {
    newTypeSymbol(ident.name, ident.loc, kind)
  }

  final def newSymbolLike(symbol: TypeSymbol): TypeSymbol = {
    newTypeSymbolWithAttr(
      symbol.denot.name.str,
      symbol.loc(this),
      symbol.denot.kind,
      symbol.denot.attr
    )
  }
}

object Symbols {

  abstract trait Symbol {
    type ThisDenotation <: Denotation

    def id: Int

    def isTermSymbol: Boolean

    def isTypeSymbol: Boolean

    ////////////////////////////////////////////////////////////////////////////
    // The only mutable member of Symbol is the denotation attached to it
    ////////////////////////////////////////////////////////////////////////////

    private[this] final var _denot: ThisDenotation = _

    ////////////////////////////////////////////////////////////////////////////
    // Common implementation
    ////////////////////////////////////////////////////////////////////////////

    final override def hashCode = id

    final override def equals(that: Any) = this eq that.asInstanceOf[AnyRef]

    // Denotation of symbol
    final def denot: ThisDenotation = if (_denot == null) unreachable else _denot

    // Set denotation
    final def withDenot(denot: ThisDenotation): this.type = {
      _denot = denot
      this
    }

    // Location of definition
    final def loc(implicit cc: CompilerContext): Loc = cc synchronized {
      cc.symbolLocations(this)
    }

    // Is this a builtin symbol
    def isBuiltin(implicit cc: CompilerContext): Boolean = {
      cc.builtins exists { _ contains this }
    }
  }

  final class TermSymbol(val id: Int) extends Symbol {
    type ThisDenotation = TermDenotation

    override def isTermSymbol = true
    override def isTypeSymbol = false

    override def toString = s"TermSymbol($id)"

    // Add an attribute
    final def addAttr(pair: (String, Expr)): this.type = {
      this withDenot denot.copy(attr = denot.attr + pair)
    }

    final def addAttr(pair: (String, Int))(implicit cc: CompilerContext): this.type = {
      this addAttr (pair._1 -> (Expr(pair._2) withLoc loc))
    }
  }

  final class TypeSymbol(val id: Int) extends Symbol {
    type ThisDenotation = TypeDenotation

    override def isTermSymbol = false
    override def isTypeSymbol = true

    override def toString = s"TypeSymbol($id)"
  }

  final object ErrorSymbol extends Symbol {
    type ThisDenotation = Nothing
    val id = -1

    override def isTermSymbol = false
    override def isTypeSymbol = false

    override def toString = s"ErrorSymbol"
  }
}
