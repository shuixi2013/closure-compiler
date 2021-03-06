/*
 * Copyright 2018 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.StaticScope;
import java.io.Serializable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scope contains information about a variable scope in JavaScript. Scopes can be nested, a scope
 * points back to its parent scope. A Scope contains information about variables defined in that
 * scope.
 *
 * <p>ES6 introduces new scoping rules, which adds some complexity to this class. In particular,
 * scopes fall into four mutually exclusive categories based on their root node: block, function,
 * module, or global. Function, module, and global scopes are collectively referred to as "non-block
 * scopes". We also define a scope as a "hoist scope" if it is a non-block scope *or* it is the
 * outermost block scope within a function (i.e. a "function block scope"). Hoist scopes are
 * important because "var" declarations are hoisted to the closest hoist scope, as opposed to ES6
 * "let" and "const" which are not hoisted, but instead added directly to whatever scope they're
 * declared in.
 *
 * <p>Finally, a caution about function scopes and function block scopes: the language does not
 * permit any shadowing to occur between them (with the exception of bleeding function names), so in
 * many situations these scopes are treated as a single scope. Under block scoping, only function
 * parameters (and optionally, bleeding function names) are declared in the function scope. It is
 * kept as a separate scope so that default parameter initializers may be evaluated in a separate
 * scope from the function body.
 *
 * @see NodeTraversal
 */
abstract class AbstractScope<S extends AbstractScope<S, V>, V extends AbstractVar<S, V>>
    implements StaticScope, Serializable {
  private final Map<String, V> vars = new LinkedHashMap<>();
  private final Map<ImplicitVar, V> implicitVars = new EnumMap<>(ImplicitVar.class);
  private final Node rootNode;

  AbstractScope(Node rootNode) {
    this.rootNode = rootNode;
  }

  /** The depth of the scope. The global scope has depth 0. */
  public abstract int getDepth();

  /** Returns the parent scope, or null if this is the global scope. */
  public abstract S getParent();

  @Override
  public final String toString() {
    return "Scope@" + rootNode;
  }

  public Scope untyped() {
    throw new IllegalStateException("untyped() called, but not an untyped scope.");
  }

  public TypedScope typed() {
    throw new IllegalStateException("typed() called, but not a typed scope.");
  }

  /**
   * @return True if this scope contains {@code other}, or is the same scope as {@code other}.
   */
  final boolean contains(S other) {
    S s = checkNotNull(other);
    while (s != null) {
      if (s == this) {
        return true;
      }
      s = s.getParent();
    }
    return false;
  }

  /**
   * Gets the container node of the scope. This is typically the FUNCTION
   * node or the global BLOCK/SCRIPT node.
   */
  // Non-final for jsdev tests
  @Override
  public Node getRootNode() {
    return rootNode;
  }

  /** Walks up the tree to find the global scope. */
  final S getGlobalScope() {
    S result = thisScope();
    while (result.getParent() != null) {
      result = result.getParent();
    }
    return result;
  }

  @Override
  public final S getParentScope() {
    return getParent();
  }

  abstract V makeImplicitVar(ImplicitVar type);

  /**
   * Undeclares a variable, to be used when the compiler optimizes out
   * a variable and removes it from the scope.
   */
  final void undeclare(V var) {
    checkState(var.scope == this);
    checkState(vars.get(var.name).equals(var));
    undeclareInteral(var);
  }

  /** Without any safety checks */
  final void undeclareInteral(V var) {
     vars.remove(var.name);
  }

  final void declareInternal(String name, V var) {
    vars.put(name, var);
  }

  final void clearVarsInternal() {
    vars.clear();
  }

  @Override
  public final V getSlot(String name) {
    return getVar(name);
  }

  @Override
  public final V getOwnSlot(String name) {
    return vars.get(name);
  }

  /**
   * Returns the variable, may be null
   */
  // Non-final for jsdev tests
  public V getVar(String name) {
    ImplicitVar implicit = name != null ? ImplicitVar.of(name) : null;
    if (implicit != null) {
      return getImplicitVar(implicit, true);
    }
    S scope = thisScope();
    while (scope != null) {
      V var = scope.getOwnSlot(name);
      if (var != null) {
        return var;
      }
      if (Var.ARGUMENTS.equals(name) && NodeUtil.isVanillaFunction(scope.getRootNode())) {
        return scope.getArgumentsVar();
      }
      // Recurse up the parent Scope
      scope = scope.getParent();
    }
    return null;
  }

  /**
   * Get a unique Var object to represent "arguments" within this scope
   */
  public final V getArgumentsVar() {
    return getImplicitVar(ImplicitVar.ARGUMENTS, false);
  }

  private final V getImplicitVar(ImplicitVar var, boolean allowDeclaredVars) {
    S scope = thisScope();
    while (scope != null) {
      if (var.isMadeByScope(scope)) {
        V result = ((AbstractScope<S, V>) scope).implicitVars.get(var);
        if (result == null) {
          ((AbstractScope<S, V>) scope).implicitVars.put(var, result = scope.makeImplicitVar(var));
        }
        return result;
      }
      V result = allowDeclaredVars ? scope.getOwnSlot(var.name) : null;
      if (result != null) {
        return result;
      }
      // Recurse up the parent Scope
      scope = scope.getParent();
    }
    return null;
  }

  /**
   * Use only when in a function block scope and want to tell if a name is either at the top of the
   * function block scope or the function parameter scope.  This obviously only applies to ES6 block
   * scopes.
   */
  public final boolean isDeclaredInFunctionBlockOrParameter(String name) {
    // In ES6, we create a separate "function parameter scope" above the function block scope to
    // handle default parameters. Since nothing in the function block scope is allowed to shadow
    // the variables in the function scope, we treat the two scopes as one in this method.
    checkState(isFunctionBlockScope());
    return isDeclared(name, false) || (getParent().isDeclared(name, false));
  }

  /**
   * Returns true if a variable is declared.
   */
  public final boolean isDeclared(String name, boolean recurse) {
    S scope = thisScope();
    while (true) {
      if (scope.getOwnSlot(name) != null) {
        return true;
      }

      if (scope.getParent() != null && recurse) {
        scope = scope.getParent();
        continue;
      }
      return false;
    }
  }

  /**
   * Return an iterable over all of the variables declared in this scope
   * (except the special 'arguments' variable).
   */
  // Non-final for jsdev tests
  public Iterable<V> getVarIterable() {
    return vars.values();
  }

  /**
   * Return an iterable over all of the variables accessible to this scope (i.e. the variables in
   * this scope and its parent scopes). Any variables declared in the local scope with the same name
   * as a variable declared in a parent scope gain precedence - if let x exists in the block scope,
   * a declaration let x from the parent scope would not be included because the parent scope's
   * variable gets shadowed.
   *
   * <p>The iterable contains variables from inner scopes before adding variables from outer parent
   * scopes.
   *
   * <p>We do not include the special 'arguments' variable.
   */
  public final Iterable<V> getAllAccessibleVariables() {
    Map<String, V> accessibleVars = new LinkedHashMap<>();
    S s = thisScope();

    while (s != null) {
      for (V v : s.getVarIterable()) {
        accessibleVars.putIfAbsent(v.getName(), v);
      }
      s = s.getParent();
    }
    return accessibleVars.values();
  }

  // Non-final for jsdev tests
  public Iterable<V> getAllSymbols() {
    return Collections.unmodifiableCollection(vars.values());
  }

  /**
   * Returns number of variables in this scope (excluding the special 'arguments' variable)
   */
  // Non-final for jsdev tests
  public int getVarCount() {
    return vars.size();
  }

  /**
   * Returns whether this is the global scope.
   */
  // Non-final for jsdev tests
  public boolean isGlobal() {
    return getParent() == null;
  }

  /**
   * Returns whether this is a local scope (i.e. not the global scope).
   */
  // Non-final for jsdev tests
  public boolean isLocal() {
    return getParent() != null;
  }

  public final boolean isBlockScope() {
    return NodeUtil.createsBlockScope(rootNode);
  }

  public final boolean isFunctionBlockScope() {
    return NodeUtil.isFunctionBlock(getRootNode());
  }

  public final boolean isFunctionScope() {
    return getRootNode().isFunction();
  }

  public final boolean isModuleScope() {
    return getRootNode().isModuleBody();
  }

  public final boolean isCatchScope() {
    return getRootNode().isNormalBlock()
        && getRootNode().hasOneChild()
        && getRootNode().getFirstChild().isCatch();
  }

  /**
   * If a var were declared in this scope, would it belong to this scope (as opposed to some
   * enclosing scope)?
   *
   * We consider function scopes to be hoist scopes. Even though it's impossible to declare a var
   * inside function parameters, it would make less sense to say that if you did declare one in
   * the function parameters, it would be hoisted somewhere else.
   */
  final boolean isHoistScope() {
    return isFunctionScope() || isFunctionBlockScope() || isGlobal() || isModuleScope();
  }

  /**
   * If a var were declared in this scope, return the scope it would be hoisted to.
   *
   * For function scopes, we return back the scope itself, since even though there is no way
   * to declare a var inside function parameters, it would make even less sense to say that
   * such declarations would be "hoisted" somewhere else.
   */
  public final S getClosestHoistScope() {
    S current = thisScope();
    while (current != null) {
      if (current.isHoistScope()) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  /**
   * Returns the closest non-block scope. This is equivalent to what the current scope would have
   * been for non-block-scope creators, and is thus useful for migrating code to use block scopes.
   */
  public final S getClosestNonBlockScope() {
    S scope = getClosestHoistScope();
    if (scope.isBlockScope()) {
      scope = scope.getParent();
      checkState(!scope.isBlockScope());
    }
    return scope;
  }

  // This is safe because any concrete subclass of AbstractScope<S> should be assignable to S.
  // While it's theoretically possible to do otherwise, such a class would be very awkward to
  // implement, and is therefore not worth worrying about.
  @SuppressWarnings("unchecked")
  private S thisScope() {
    return (S) this;
  }

  /** Performs simple validity checks on when constructing a child scope. */
  void checkChildScope(S parent) {
    checkNotNull(parent);
    checkArgument(NodeUtil.createsScope(rootNode), rootNode);
    checkArgument(
        rootNode != parent.getRootNode(),
        "rootNode should not be the parent's root node: %s", rootNode);
  }

  /** Performs simple validity checks on when constructing a root scope. */
  void checkRootScope() {
    // TODO(tbreisacher): Can we tighten this to just NodeUtil.createsScope?
    checkArgument(
        NodeUtil.createsScope(rootNode) || rootNode.isScript() || rootNode.isRoot(), rootNode);
  }

  /**
   * The three implicit var types, which are defined implicitly (at least) in
   * every vanilla function scope without actually being declared.
   */
  enum ImplicitVar {
    ARGUMENTS("arguments"),
    SUPER("super"),
    // TODO(sdh): Expand THIS.isMadeByScope to check super.isMadeByScope(scope) || scope.isGlobal()
    // Currently this causes a number of problems (see b/74980936), but could eventually lead to
    // better type information.  We might also want to restrict this so that module-root scopes
    // explicitly *don't* have access to the global this, though I think this is more than just
    // returning false in isMadeByScope - rather, getVar() needs to stop checking and immediately
    // return null.
    THIS("this");

    final String name;

    ImplicitVar(String name) {
      this.name = name;
    }

    /** Whether this kind of implicit variable is created/owned by the given scope. */
    boolean isMadeByScope(AbstractScope<?, ?> scope) {
      return NodeUtil.isVanillaFunction(scope.getRootNode());
    }

    static ImplicitVar of(String name) {
      switch (name) {
        case "arguments":
          return ARGUMENTS;
        case "super":
          return SUPER;
        case "this":
          return THIS;
        default:
          return null;
      }
    }
  }
}
