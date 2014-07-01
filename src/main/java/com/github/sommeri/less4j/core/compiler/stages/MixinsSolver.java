package com.github.sommeri.less4j.core.compiler.stages;

import java.util.ArrayList;
import java.util.List;

import com.github.sommeri.less4j.LessCompiler.Configuration;
import com.github.sommeri.less4j.core.ast.ASTCssNode;
import com.github.sommeri.less4j.core.ast.Body;
import com.github.sommeri.less4j.core.ast.BodyOwner;
import com.github.sommeri.less4j.core.ast.Declaration;
import com.github.sommeri.less4j.core.ast.DetachedRuleset;
import com.github.sommeri.less4j.core.ast.DetachedRulesetReference;
import com.github.sommeri.less4j.core.ast.Expression;
import com.github.sommeri.less4j.core.ast.GeneralBody;
import com.github.sommeri.less4j.core.ast.MixinReference;
import com.github.sommeri.less4j.core.ast.ReusableStructure;
import com.github.sommeri.less4j.core.compiler.expressions.ExpressionFilter;
import com.github.sommeri.less4j.core.compiler.expressions.ExpressionsEvaluator;
import com.github.sommeri.less4j.core.compiler.expressions.GuardValue;
import com.github.sommeri.less4j.core.compiler.expressions.MixinsGuardsValidator;
import com.github.sommeri.less4j.core.compiler.scopes.FullMixinDefinition;
import com.github.sommeri.less4j.core.compiler.scopes.IScope;
import com.github.sommeri.less4j.core.compiler.scopes.InScopeSnapshotRunner;
import com.github.sommeri.less4j.core.compiler.scopes.InScopeSnapshotRunner.IFunction;
import com.github.sommeri.less4j.core.compiler.scopes.InScopeSnapshotRunner.ITask;
import com.github.sommeri.less4j.core.compiler.scopes.ScopeFactory;
import com.github.sommeri.less4j.core.compiler.scopes.ScopeManipulation;
import com.github.sommeri.less4j.core.problems.ProblemsHandler;

class MixinsSolver {

  private final ProblemsHandler problemsHandler;
  private final ReferencesSolver parentSolver;
  private final AstNodesStack semiCompiledNodes;
  private final Configuration configuration;
  private final DefaultGuardHelper defaultGuardHelper;
  private final ScopeManipulation scopeManipulation = new ScopeManipulation();

  public MixinsSolver(ReferencesSolver parentSolver, AstNodesStack semiCompiledNodes, ProblemsHandler problemsHandler, Configuration configuration) {
    this.parentSolver = parentSolver;
    this.semiCompiledNodes = semiCompiledNodes;
    this.problemsHandler = problemsHandler;
    this.configuration = configuration;
    this.defaultGuardHelper = new DefaultGuardHelper(problemsHandler);
  }

  private BodyCompilationResult resolveCalledBody(final IScope callerScope, final BodyOwner<?> bodyOwner, final IScope bodyWorkingScope) {
    final ExpressionsEvaluator expressionEvaluator = new ExpressionsEvaluator(bodyWorkingScope, problemsHandler, configuration);

    final IScope referencedMixinScope = bodyWorkingScope;
    // ... and I'm starting to see the point of closures ...
    return InScopeSnapshotRunner.runInOriginalDataSnapshot(referencedMixinScope, new IFunction<BodyCompilationResult>() {

      @Override
      public BodyCompilationResult run() {
        // compile referenced mixin - keep the original copy unchanged
        List<ASTCssNode> replacement = compileBody(bodyOwner.getBody(), referencedMixinScope);

        // collect variables and mixins to be imported
        IScope returnValues = ScopeFactory.createDummyScope();
        returnValues.addFilteredVariables(new ImportedScopeFilter(expressionEvaluator, callerScope), referencedMixinScope);
        List<FullMixinDefinition> unmodifiedMixinsToImport = referencedMixinScope.getAllMixins();
        
        List<FullMixinDefinition> allMixinsToImport = scopeManipulation.mixinsToImport(callerScope, referencedMixinScope, unmodifiedMixinsToImport);
        returnValues.addAllMixins(allMixinsToImport);

        return new BodyCompilationResult((ASTCssNode) bodyOwner, replacement, returnValues);
      }

    });
  }

  private List<ASTCssNode> compileBody(Body body, IScope scopeSnapshot) {
    semiCompiledNodes.push(body.getParent());
    try {
      Body bodyClone = body.clone();
      parentSolver.unsafeDoSolveReferences(bodyClone, scopeSnapshot);
      return bodyClone.getMembers();
    } finally {
      semiCompiledNodes.pop();
    }
  }

  private void shiftComments(ASTCssNode reference, GeneralBody result) {
    List<ASTCssNode> childs = result.getMembers();
    if (!childs.isEmpty()) {
      childs.get(0).addOpeningComments(reference.getOpeningComments());
      childs.get(childs.size() - 1).addTrailingComments(reference.getTrailingComments());
    }
  }

  private IScope buildMixinsArguments(MixinReference reference, IScope referenceScope, FullMixinDefinition mixin) {
    ArgumentsBuilder builder = new ArgumentsBuilder(reference, mixin.getMixin(), new ExpressionsEvaluator(referenceScope, problemsHandler, configuration), problemsHandler);
    return builder.build();
  }

  public GeneralBody buildMixinReferenceReplacement(final MixinReference reference, final IScope callerScope, List<FullMixinDefinition> mixins) {
    GeneralBody result = new GeneralBody(reference.getUnderlyingStructure());
    if (mixins.isEmpty())
      return result;

    //candidate mixins with information about their default() function use are stored here
    final List<BodyCompilationResult> compiledMixins = new ArrayList<BodyCompilationResult>();

    for (final FullMixinDefinition fullMixin : mixins) {
      final ReusableStructure mixin = fullMixin.getMixin();
      final IScope mixinScope = fullMixin.getScope();

      // the following needs to run in snapshot because calculateMixinsWorkingScope modifies that scope
      InScopeSnapshotRunner.runInLocalDataSnapshot(mixinScope.getParent(), new ITask() {

        @Override
        public void run() {
          IScope mixinArguments = buildMixinsArguments(reference, callerScope, fullMixin);
          IScope mixinWorkingScope = scopeManipulation.calculateMixinsWorkingScope(callerScope, mixinArguments, mixinScope);

          MixinsGuardsValidator guardsValidator = new MixinsGuardsValidator(mixinWorkingScope, problemsHandler, configuration);
          GuardValue guardValue = guardsValidator.evaluateGuards(mixin);

          if (guardValue != GuardValue.DO_NOT_USE) {
            //OPTIMIZATION POSSIBLE: there is no need to compile mixins at this point, some of them are not going to be 
            //used and create snapshot operation is cheap now. It should be done later on.
            BodyCompilationResult compiled = resolveCalledBody(callerScope, fullMixin.getMixin(), mixinWorkingScope);
            //mark the mixin according to its default() function use 
            compiled.setGuardValue(guardValue);
            //store the mixin as candidate
            compiledMixins.add(compiled);
          }
        }
      });
    }

    // filter out mixins we do not want to use  
    List<BodyCompilationResult> mixinsToBeUsed = defaultGuardHelper.chooseMixinsToBeUsed(compiledMixins, reference);

    // update mixin replacements and update scope with imported variables and mixins
    for (BodyCompilationResult compiled : mixinsToBeUsed) {
      result.addMembers(compiled.getReplacement());
      callerScope.addToDataPlaceholder(compiled.getReturnValues());
    }

    callerScope.closeDataPlaceholder();
    resolveImportance(reference, result);
    shiftComments(reference, result);

    return result;
  }

  public GeneralBody buildDetachedRulesetReplacement(DetachedRulesetReference reference, IScope callerScope, DetachedRuleset detachedRuleset, IScope detachedRulesetScope) {
    //FIXME: !!!!!!!!!!!! this should run in detachedRulesetScope parent snapshot - check whether it is the case
    IScope mixinWorkingScope = scopeManipulation.calculateBodyWorkingScope(callerScope, detachedRulesetScope);
    BodyCompilationResult compiled = resolveCalledBody(callerScope, detachedRuleset, mixinWorkingScope);
    GeneralBody result = new GeneralBody(reference.getUnderlyingStructure());

    result.addMembers(compiled.getReplacement());
    callerScope.addToDataPlaceholder(compiled.getReturnValues());
    callerScope.closeDataPlaceholder();

    //resolveImportance(reference, result);
    shiftComments(reference, result);

    return result;
  }

  private void resolveImportance(MixinReference reference, GeneralBody result) {
    if (reference.isImportant()) {
      declarationsAreImportant(result);
    }
  }

  @SuppressWarnings("rawtypes")
  private void declarationsAreImportant(Body result) {
    for (ASTCssNode kid : result.getMembers()) {
      if (kid instanceof Declaration) {
        Declaration declaration = (Declaration) kid;
        declaration.setImportant(true);
      } else if (kid instanceof BodyOwner<?>) {
        BodyOwner owner = (BodyOwner) kid;
        declarationsAreImportant(owner.getBody());
      }
    }
  }

  //FIXME !!!! refactor and clean, unify with references 
  class ImportedScopeFilter implements ExpressionFilter {

    private final ExpressionsEvaluator expressionEvaluator;
    private final IScope importTargetScope;
    private final ScopeManipulation scopeManipulation = new ScopeManipulation();

    public ImportedScopeFilter(ExpressionsEvaluator expressionEvaluator, IScope importTargetScope) {
      super();
      this.expressionEvaluator = expressionEvaluator;
      this.importTargetScope = importTargetScope;
    }

    public Expression apply(Expression input) {
      Expression result = expressionEvaluator.evaluate(input);
      //FIXME: !!!!!!!! probably not necessary? expression evaluator should take care of this
      IScope newScope = apply(result.getScope());
      result.setScope(newScope);
      return result;
    }

    private IScope apply(IScope input) {
      if (input == null)
        return importTargetScope;

      return scopeManipulation.constructImportedBodyScope(importTargetScope, input);
    }

  }

}
