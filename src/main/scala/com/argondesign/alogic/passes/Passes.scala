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
// Driver to apply all compiler passes to trees
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.passes

import com.argondesign.alogic.ast.Trees._
import com.argondesign.alogic.backend.CodeGeneration
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.typer.Typer

import scala.util.ChainingSyntax

object Passes extends ChainingSyntax {

  // All trees are transformed with the given pass before the next pass begins
  def apply(trees: List[Tree])(implicit cc: CompilerContext): List[Tree] = {
    val passes: List[Pass] = List(
      ////////////////////////////////////////////////////////////////////////
      // Front-end
      ////////////////////////////////////////////////////////////////////////
      Checker,
      Namer,
      UnusedCheck(postSpecialize = false),
      Specialize,
      ResolveDictPorts,
      UnusedCheck(postSpecialize = true),
      // Any passes between here and the middle end can only perform checks
      // and cannot re-write any trees unless errors have been detected
      Typer(externalRefs = false),
      Typer(externalRefs = true),
      PortCheckA,
      ////////////////////////////////////////////////////////////////////////
      // Middle-end
      ////////////////////////////////////////////////////////////////////////
      ReplaceUnaryTicks, // This must be first as TypeAssigner cannot handle unary '
      ResolvePolyFunc,
      AddCasts,
      FoldTypeRefs,
      Desugar,
      FoldExprInTypes,
      InlineUnsizedConst,
      FoldExpr(foldRefs = false),
      PortCheckB,
      ConvertMultiConnect,
      LowerPipeline,
      LiftEntities,
      LowerLoops,
      AnalyseCallGraph,
      ConvertLocalDecls,
      RemoveStructuralSharing,
      ConvertControl,
      AllocStates,
      CreateStateSystem,
      Replace1Stacks,
      // TODO: Replace1Arrays
      DefaultStorage,
      // TODO: CheckAcceptUsage
      LowerFlowControlA,
      LowerFlowControlB,
      LowerFlowControlC,
      LowerSrams(),
      LowerStacks,
      LowerRegPorts,
      LowerArrays,
      LiftSrams,
      SplitStructsA,
      SplitStructsB,
      SplitStructsC,
      LowerVectors,
      AddCasts,
      FoldExpr(foldRefs = false),
      SimplifyCat,
      InferImplications,
      FoldStmt,
      SimplifyConditionals,
      ////////////////////////////////////////////////////////////////////////
      // Back-end
      ////////////////////////////////////////////////////////////////////////
      RenameSymbols,
      LowerVariables,
      LowerInterconnect,
      PropagateImplications,
      RemoveStructuralSharing,
      FoldStmt,
      OptimizeClearOnStall,
      // TODO: LowerGo
      DefaultAssignments,
      RemoveUnused,
      RemoveRedundantBlocks,
      RenameSymbols,
      // TODO: RenameKeywords
      // TODO: final check pass to make sure everything is well-formed
      WriteModuleManifest,
      CodeGeneration
    )

    // Fold passes over the trees
    passes.foldLeft(trees) { applyPass(_, _) }
  }

  private def applyPass(trees: List[Tree], pass: Pass)(implicit cc: CompilerContext): List[Tree] = {
    if (cc.hasError) {
      // If we have encountered errors in an earlier pass, skip any later passes
      trees
    } else {
      // Apply the pass
      val results = pass(trees)

      // Dump entities if required
      if (cc.settings.dumpTrees) {
        results foreach {
          case Root(_, entity) => cc.dumpEntity(entity, s".${cc.passNumber}.${pass.name}")
          case entity: Entity  => cc.dumpEntity(entity, s".${cc.passNumber}.${pass.name}")
          case _               => ()
        }
      }

      // Return the results
      results
    }
  } tap { _ =>
    // Increment the pass index
    cc.passNumber += 1
    // Emit any messages generated by this pass
    cc.emitMessages()
  }
}
