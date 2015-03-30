package com.github.sommeri.less4j.core.compiler.stages;

import java.util.ArrayList;
import java.util.LinkedList;
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
import com.github.sommeri.less4j.core.ast.KeywordExpression;
import com.github.sommeri.less4j.core.ast.ListExpression;
import com.github.sommeri.less4j.core.ast.ListExpressionOperator;
import com.github.sommeri.less4j.core.ast.MixinReference;
import com.github.sommeri.less4j.core.ast.ReusableStructure;
import com.github.sommeri.less4j.core.compiler.expressions.ExpressionFilter;
import com.github.sommeri.less4j.core.compiler.expressions.ExpressionEvaluator;
import com.github.sommeri.less4j.core.compiler.expressions.ExpressionManipulator;
import com.github.sommeri.less4j.core.compiler.expressions.GuardValue;
import com.github.sommeri.less4j.core.compiler.expressions.MixinsGuardsValidator;
import com.github.sommeri.less4j.core.compiler.scopes.FoundMixin;
import com.github.sommeri.less4j.core.compiler.scopes.FullMixinDefinition;
import com.github.sommeri.less4j.core.compiler.scopes.IScope;
import com.github.sommeri.less4j.core.compiler.scopes.InScopeSnapshotRunner;
import com.github.sommeri.less4j.core.compiler.scopes.InScopeSnapshotRunner.IFunction;
import com.github.sommeri.less4j.core.compiler.scopes.InScopeSnapshotRunner.ITask;
import com.github.sommeri.less4j.core.compiler.scopes.ScopeFactory;
import com.github.sommeri.less4j.core.compiler.scopes.view.ScopeView;
import com.github.sommeri.less4j.core.problems.ProblemsHandler;
import com.github.sommeri.less4j.utils.ArraysUtils;
import com.github.sommeri.less4j.utils.debugonly.DebugUtils;

class MixinsRulesetsSolver {

  private final ProblemsHandler problemsHandler;
  private final ReferencesSolver parentSolver;
  private final AstNodesStack semiCompiledNodes;
  private final Configuration configuration;
  private final DefaultGuardHelper defaultGuardHelper;
  private final CallerCalleeScopeJoiner scopeManipulation = new CallerCalleeScopeJoiner();
  private final ExpressionManipulator expressionManipulator = new ExpressionManipulator();
  
  public static int id = 0;
  public static IScope watchedScopeInstance = null; 

  public MixinsRulesetsSolver(ReferencesSolver parentSolver, AstNodesStack semiCompiledNodes, ProblemsHandler problemsHandler, Configuration configuration) {
    this.parentSolver = parentSolver;
    this.semiCompiledNodes = semiCompiledNodes;
    this.problemsHandler = problemsHandler;
    this.configuration = configuration;
    this.defaultGuardHelper = new DefaultGuardHelper(problemsHandler);
  }

  private BodyCompilationData resolveCalledBody(final IScope callerScope, final BodyOwner<?> bodyOwner, final IScope bodyWorkingScope, final ReturnMode returnMode) {
    final ExpressionEvaluator expressionEvaluator = new ExpressionEvaluator(bodyWorkingScope, problemsHandler, configuration);

    final IScope referencedMixinScope = bodyWorkingScope;
    // ... and I'm starting to see the point of closures ...
    return InScopeSnapshotRunner.runInLocalDataSnapshot(referencedMixinScope, new IFunction<BodyCompilationData>() {
    //return InScopeSnapshotRunner.runInOriginalDataSnapshot(referencedMixinScope, new IFunction<BodyCompilationData>() {

      @Override
      public BodyCompilationData run() {
        // compile referenced mixin - keep the original copy unchanged
        List<ASTCssNode> replacement = compileBody(bodyOwner.getBody(), referencedMixinScope);

        // collect variables and mixins to be imported
        IScope returnValues = ScopeFactory.createDummyScope();
        if (returnMode == ReturnMode.MIXINS_AND_VARIABLES) {
          DebugUtils u = new DebugUtils();
          u.scopeTest(callerScope, "callerScope");
          u.scopeTest(referencedMixinScope, "referencedMixinScope");// switching between callerScope and referencedMixinScope on the line down
          returnValues.addFilteredVariables(new ImportedScopeFilter(expressionEvaluator, callerScope), referencedMixinScope);
        }
        List<FullMixinDefinition> unmodifiedMixinsToImport = referencedMixinScope.getAllMixins();

        List<FullMixinDefinition> allMixinsToImport = scopeManipulation.mixinsToImport(callerScope, referencedMixinScope, unmodifiedMixinsToImport);
        returnValues.addAllMixins(allMixinsToImport);
        
        for (FullMixinDefinition fullMixinDefinition : allMixinsToImport) {
          if (fullMixinDefinition.getMixin().getNames().get(0).asString().equals(".bananas")) {
            watchedScopeInstance = fullMixinDefinition.getScope();
          }
        }
        

        return new BodyCompilationData(bodyOwner, replacement, returnValues);
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
    ArgumentsBuilder builder = new ArgumentsBuilder(reference, mixin.getMixin(), new ExpressionEvaluator(referenceScope, problemsHandler, configuration), problemsHandler);
    return builder.build();
  }

  public GeneralBody buildMixinReferenceReplacement(final MixinReference reference, final IScope callerScope, List<FoundMixin> mixins) {
    GeneralBody result = new GeneralBody(reference.getUnderlyingStructure());
    if (mixins.isEmpty())
      return result;

    //candidate mixins with information about their default() function use are stored here
    final List<BodyCompilationData> compiledMixins = new ArrayList<BodyCompilationData>();

    for (final FoundMixin fullMixin : mixins) {
      final ReusableStructure mixin = fullMixin.getMixin();
      final IScope mixinScope = fullMixin.getScope();

      final BodyCompilationData data = new BodyCompilationData(fullMixin.getMixin(), null);
      //FIXME !!!! remove this did not helped
      final ScopeView callerScopeCopy = ScopeFactory.createSaveableView(callerScope);
      callerScopeCopy.saveLocalDataForTheWholeWayUp();
      // the following needs to run in snapshot because calculateMixinsWorkingScope modifies that scope
      InScopeSnapshotRunner.runInLocalDataSnapshot(mixinScope.getParent(), new ITask() {

        @Override
        public void run() {
          // add arguments
          IScope mixinArguments = buildMixinsArguments(reference, callerScope, fullMixin);
          data.setMixinArguments(mixinArguments);
          mixinScope.getParent().add(mixinArguments); //this gets lost
          ScopeView mixinWorkingScope = scopeManipulation.joinIfIndependent(callerScope, mixinScope);
          data.setMixinWorkingScope(mixinWorkingScope);

          MixinsGuardsValidator guardsValidator = new MixinsGuardsValidator(mixinWorkingScope, problemsHandler, configuration);
          GuardValue guardValue = guardsValidator.evaluateGuards(mixin);

          LinkedList<GuardValue> namespacesGuards = fullMixin.getGuardsOnPath();
          namespacesGuards.add(guardValue);
          guardValue = guardsValidator.andGuards(namespacesGuards);
          data.setGuardValue(guardValue);
        }
      }); //end of InScopeSnapshotRunner.runInLocalDataSnapshot

      InScopeSnapshotRunner.runInLocalDataSnapshot(mixinScope.getParent(), new ITask() {

        @Override
        public void run() {
          // add arguments
          IScope mixinArguments = data.getMixinArguments();
          ScopeView mixinWorkingScope = data.getMixinWorkingScope();
          mixinWorkingScope.getParent().add(mixinArguments); 
          mixinWorkingScope.saveLocalDataForTheWholeWayUp();
          data.setMixinWorkingScope(mixinWorkingScope);

          easilyMoveable(callerScope, compiledMixins, fullMixin, data);
        }
      }); //end of InScopeSnapshotRunner.runInLocalDataSnapshot
}

    // filter out mixins we do not want to use  
    List<BodyCompilationData> mixinsToBeUsed = defaultGuardHelper.chooseMixinsToBeUsed(compiledMixins, reference);

    // update mixin replacements and update scope with imported variables and mixins
    for (BodyCompilationData data : mixinsToBeUsed) {
      result.addMembers(data.getReplacement());
      callerScope.addToDataPlaceholder(data.getReturnValues());
    }

    callerScope.closeDataPlaceholder();
    resolveImportance(reference, result);
    shiftComments(reference, result);

    return result;
  }

  private void easilyMoveable(final IScope callerScope, final List<BodyCompilationData> compiledMixins, final FoundMixin fullMixin, final BodyCompilationData data) {
    GuardValue guardValue2 = data.getGuardValue();
    IScope mixinWorkingScope2 = data.getMixinWorkingScope();
    if (guardValue2 != GuardValue.DO_NOT_USE) {
      //OPTIMIZATION POSSIBLE: there is no need to compile mixins at this point, some of them are not going to be 
      //used and create snapshot operation is cheap now. It should be done later on.
      BodyCompilationData compiled = resolveCalledBody(callerScope, fullMixin.getMixin(), mixinWorkingScope2, ReturnMode.MIXINS_AND_VARIABLES);
      // *************************************************  
      data.setReplacement(compiled.getReplacement());
      data.setReturnValues(compiled.getReturnValues());
      //store the mixin as candidate
      compiledMixins.add(data);
    }
  }

  private void scopetest(IScope scope, Object id) {
    if (scope ==null) {
      System.out.println("scopetest skipped");
      return ;
    }
    try {
      System.out.println("--- Name: " +id+ " scope: " + scope);
      Expression value = scope.getValue("@width");
      
      if (value==null)
        System.out.println("@width: " + value + " !!!!!!!!!!!!!!!");
      else 
        System.out.println("@width: " + value);
    } catch (Throwable th) {
      th.printStackTrace();
    }
  }

  public GeneralBody buildDetachedRulesetReplacement(DetachedRulesetReference reference, IScope callerScope, DetachedRuleset detachedRuleset, IScope detachedRulesetScope) {
    IScope mixinWorkingScope = scopeManipulation.joinIfIndependent(callerScope, detachedRulesetScope);
    BodyCompilationData compiled = resolveCalledBody(callerScope, detachedRuleset, mixinWorkingScope, ReturnMode.MIXINS);
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
        addImportantKeyword(declaration);
      } else if (kid instanceof BodyOwner<?>) {
        BodyOwner owner = (BodyOwner) kid;
        declarationsAreImportant(owner.getBody());
      }
    }
  }

  private void addImportantKeyword(Declaration declaration) {
    Expression expression = declaration.getExpression();
    if (expressionManipulator.isImportant(expression))
      return;

    //FIXME !!!!!!!!!! correct underlying - or correct  keyword!!!
    KeywordExpression important = new KeywordExpression(expression.getUnderlyingStructure(), "!important", true);

    ListExpression list = expressionManipulator.findRightmostSpaceSeparatedList(expression);
    if (list == null) {
      list = new ListExpression(expression.getUnderlyingStructure(), ArraysUtils.asList(expression), new ListExpressionOperator(expression.getUnderlyingStructure(), ListExpressionOperator.Operator.EMPTY_OPERATOR));
    }
    list.addExpression(important);
    list.configureParentToAllChilds();

    declaration.setExpression(list);
    list.setParent(declaration);
  }

  class ImportedScopeFilter implements ExpressionFilter {

    private final ExpressionEvaluator expressionEvaluator;
    private final IScope importTargetScope;
    private final CallerCalleeScopeJoiner scopeManipulation = new CallerCalleeScopeJoiner();

    public ImportedScopeFilter(ExpressionEvaluator expressionEvaluator, IScope importTargetScope) {
      super();
      this.expressionEvaluator = expressionEvaluator;
      this.importTargetScope = importTargetScope;
    }

    public Expression apply(Expression input) {
      Expression result = expressionEvaluator.evaluate(input);
      IScope newScope = apply(result.getScope());
      result.setScope(newScope);
      return result;
    }

    private IScope apply(IScope input) {
      if (input == null)
        return importTargetScope;

      return scopeManipulation.joinIfIndependentAndPreserveContent(importTargetScope, input);
    }

    @Override
    public boolean accepts(String name, Expression value) {
      return true;
    }

  }

}