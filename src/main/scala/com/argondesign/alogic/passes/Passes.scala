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
import com.argondesign.alogic.core.CompilerContext
import com.argondesign.alogic.typer.Typer
import com.argondesign.alogic.util.FollowedBy._

object Passes {

  // All trees are transformed with the given pass before the next pass begins
  def apply(trees: List[Tree])(implicit cc: CompilerContext): List[Tree] = {
    val passes: List[Pass] = List(
      ////////////////////////////////////////////////////////////////////////
      // Front-end
      ////////////////////////////////////////////////////////////////////////
      Checker,
      Namer,
      Desugar,
      Typer,
      ////////////////////////////////////////////////////////////////////////
      // Middle-end
      ////////////////////////////////////////////////////////////////////////
      ConvertMultiConnect,
      FoldExpr(assignTypes = true, foldRefs = false),
      SpecializeParamA,
      SpecializeParamB,
      SpecializeParamC,
      FoldExpr(assignTypes = true, foldRefs = false),
      LowerPipeline,
      LiftEntities,
      LowerLoops,
      AnalyseCallGraph,
      ConvertLocalDecls,
      ConvertControl,
      AllocStates,
      Replace1Stacks,
      // TODO: Replace1Arrays
      DefaultStorage,
      // TODO: CheckAcceptUsage
      LowerFlowControlA,
      LowerFlowControlB,
      LowerFlowControlC,
      // TODO: CheckPureExpressionInStatementPosition
      LowerRegPorts,
      LowerStacks,
      SplitStructsA,
      SplitStructsB,
      SplitStructsC,
      FoldExpr(assignTypes = true, foldRefs = false),
      SimplifyCat,
      ////////////////////////////////////////////////////////////////////////
      // Back-end
      ////////////////////////////////////////////////////////////////////////
      LowerFlops,
      LowerArrays,
      LowerInterconnect,
      // TODO: LowerGo
      DefaultAssignments,
      RemoveUnused,
      RemoveRedundantBlocks,
      RenameClashingTerms
      // TODO: RenameKeywords
      // TODO: final check pass to make sure everything is well-formed
    )

    // Fold passes over the trees
    (trees /: passes) { doPhase(_, _) }
  }

  private def doPhase(trees: List[Tree], pass: Pass)(implicit cc: CompilerContext): List[Tree] = {
    // If we have encountered errors in an earlier pass, skip any later passes
    if (cc.hasError) trees else pass(trees)
  } followedBy {
    // Increment the pass index
    cc.passNumber += 1
    // Emit any messages generated by this pass
    cc.emitMessages(Console.err)
  }
}
