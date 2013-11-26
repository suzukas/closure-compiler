/*
 * Copyright 2013 The Closure Compiler Authors.
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

package com.google.javascript.jscomp.newtypes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.Map;

/**
 *
 * @author blickly@google.com (Ben Lickly)
 * @author dimvar@google.com (Dimitris Vardoulakis)
 */
public class FunctionType {
  private final ImmutableList<JSType> requiredFormals;
  private final ImmutableList<JSType> optionalFormals;
  private final JSType restFormals;
  private final JSType returnType;
  private final boolean isLoose;
  private final ImmutableMap<String, JSType> outerVarPreconditions;
  // non-null iff this is a constructor
  private final NominalType klass;

  private FunctionType(
      ImmutableList<JSType> requiredFormals,
      ImmutableList<JSType> optionalFormals,
      JSType restFormals,
      JSType retType,
      NominalType klass,
      ImmutableMap<String, JSType> outerVars,
      boolean isLoose) {
    this.requiredFormals = requiredFormals;
    this.optionalFormals = optionalFormals;
    this.restFormals = restFormals;
    this.returnType = retType;
    this.klass = klass;
    this.outerVarPreconditions = outerVars;
    this.isLoose = isLoose;
  }

  public void checkValid() {
    if (isTopFunction()) {
      return;
    }
    for (JSType formal : requiredFormals) {
      Preconditions.checkState(formal != null);
    }
    for (JSType formal : optionalFormals) {
      Preconditions.checkState(formal != null);
    }
    Preconditions.checkState(returnType != null);
  }

  public boolean isLoose() {
    return isLoose;
  }

  FunctionType withLoose() {
    if (isTopFunction()) {
      return LOOSE_TOP_FUNCTION;
    }
    return new FunctionType(
        requiredFormals, optionalFormals, restFormals, returnType, klass,
        outerVarPreconditions, true);
  }

  static FunctionType normalized(
      List<JSType> requiredFormals,
      List<JSType> optionalFormals,
      JSType restFormals,
      JSType retType,
      NominalType klass,
      Map<String, JSType> outerVars,
      boolean isLoose) {
    if (requiredFormals == null) {
      requiredFormals = ImmutableList.of();
    }
    if (optionalFormals == null) {
      optionalFormals = ImmutableList.of();
    }
    if (outerVars == null) {
      outerVars = ImmutableMap.of();
    }
    if (restFormals != null) {
      // Remove trailing optional params w/ type equal to restFormals
      for (int i = optionalFormals.size() - 1; i >= 0; i--) {
        if (restFormals.equals(optionalFormals.get(i))) {
          optionalFormals.remove(i);
        } else {
          break;
        }
      }
    }
    return new FunctionType(
        ImmutableList.copyOf(requiredFormals),
        ImmutableList.copyOf(optionalFormals),
        restFormals, retType, klass,
        ImmutableMap.copyOf(outerVars), isLoose);
  }

  @VisibleForTesting
  static JSType makeJSType(
      ImmutableList<JSType> requiredFormals,
      ImmutableList<JSType> optionalFormals,
      JSType restFormals,
      JSType retType) {
    return JSType.fromFunctionType(FunctionType.normalized(
        requiredFormals, optionalFormals, restFormals, retType,
        null, null, false));
  }

  // This function is a subtype of every function (callable in all contexts)
  static final FunctionType BOTTOM_FUNCTION = FunctionType.normalized(
      null, null, JSType.TOP, JSType.BOTTOM, null, null, false);

  // We want to warn about argument mismatch, so we don't consider a function
  // with N required arguments to have restFormals of type TOP.
  // But we allow joins (eg after an IF) to change arity, eg,
  // number->number \/ number,number->number = number,number->number

  // Theoretically, the top function takes an infinite number of required
  // arguments of type BOTTOM and returns TOP. If this function is ever called,
  // it's a type error. Despite that, we want to represent it and not go
  // directly to JSType.TOP, to avoid spurious warnings.
  // Eg, after an IF, we may see a type (number | top_function); this type could
  // get specialized to number and used legitimately.

  // We can't represent the theoretical top function, so we special-case
  // TOP_FUNCTION below. However, the outcome is the same; if our top function
  // is ever called, a warning is inevitable.
  public static final FunctionType TOP_FUNCTION = new FunctionType(
      // Call the constructor directly to set fields to null
      null, null, null, null, null, null, false);
  public static final FunctionType LOOSE_TOP_FUNCTION = new FunctionType(
      // Call the constructor directly to set fields to null
      null, null, null, null, null, null, true);

  public boolean isTopFunction() {
    if (requiredFormals == null) {
      Preconditions.checkState(
          this == TOP_FUNCTION || this == LOOSE_TOP_FUNCTION);
    }
    return this == TOP_FUNCTION || this == LOOSE_TOP_FUNCTION;
  }

  public boolean isBottomFunction() {
    return this.equals(BOTTOM_FUNCTION);
  }

  public boolean isConstructor() {
    return klass != null;
  }

  // 0-indexed
  // Returns null if argpos indexes past the arguments
  public JSType getFormalType(int argpos) {
    Preconditions.checkArgument(!isTopFunction());
    checkValid();
    int numReqFormals = requiredFormals.size();
    if (argpos < numReqFormals) {
      Preconditions.checkState(null != requiredFormals.get(argpos));
      return requiredFormals.get(argpos);
    } else if (argpos < numReqFormals + optionalFormals.size()) {
      Preconditions.checkState(
          null != optionalFormals.get(argpos - numReqFormals));
      return optionalFormals.get(argpos - numReqFormals);
    } else {
      return restFormals;
    }
  }

  public JSType getReturnType() {
    Preconditions.checkArgument(!isTopFunction());
    if (isConstructor()) {
      return JSType.fromObjectType(ObjectType.fromClass(klass));
    } else {
      return returnType;
    }
  }

  public JSType getOuterVarPrecondition(String name) {
    Preconditions.checkArgument(!isTopFunction());
    return outerVarPreconditions.get(name);
  }

  public int getMinArity() {
    Preconditions.checkArgument(!isTopFunction());
    return requiredFormals.size();
  }

  public int getMaxArity() {
    Preconditions.checkArgument(!isTopFunction());
    if (restFormals != null) {
      return Integer.MAX_VALUE; // "Infinite" arity
    } else {
      return requiredFormals.size() + optionalFormals.size();
    }
  }

  public JSType getTypeOfThis() {
    Preconditions.checkNotNull(klass);
    return JSType.fromObjectType(ObjectType.fromClass(klass));
  }

  public JSType createConstructorObject() {
    Preconditions.checkState(klass != null);
    return klass.createConstructorObject(this);
  }

  // Returns non-null JSType
  private static JSType nullAcceptingJoin(JSType t1, JSType t2) {
    Preconditions.checkArgument(t1 != null || t2 != null);
    if (t1 == null) {
      return t2;
    } else if (t2 == null) {
      return t1;
    }
    return JSType.join(t1, t2);
  }

  // Returns non-null JSType
  private static JSType nullAcceptingMeet(JSType t1, JSType t2) {
    Preconditions.checkArgument(t1 != null || t2 != null);
    if (t1 == null) {
      return t2;
    } else if (t2 == null) {
      return t1;
    }
    return JSType.meet(t1, t2);
  }

  private static FunctionType looseJoin(FunctionType f1, FunctionType f2) {
    Preconditions.checkArgument(f1.isLoose() || f2.isLoose());

    FunctionTypeBuilder builder = new FunctionTypeBuilder();
    int minRequiredArity = Math.min(f1.getMinArity(), f2.getMinArity());
    for (int i = 0; i < minRequiredArity; i++) {
      builder.addReqFormal(nullAcceptingJoin(
          f1.getFormalType(i), f2.getFormalType(i)));
    }
    int maxTotalArity = Math.max(
        f1.requiredFormals.size() + f1.optionalFormals.size(),
        f2.requiredFormals.size() + f2.optionalFormals.size());
    for (int i = minRequiredArity; i < maxTotalArity; i++) {
      builder.addOptFormal(nullAcceptingJoin(
          f1.getFormalType(i), f2.getFormalType(i)));
    }
    // Loose types never have varargs, because there is no way for that
    // information to make it to a function summary
    return builder.addRetType(nullAcceptingJoin(f1.returnType, f2.returnType))
        .addLoose().buildFunction();
  }

  public boolean isSubtypeOf(FunctionType other) {
    // t1 <= t2 iff t2 = t1 \/ t2 doesn't hold always,
    // so we first create a new type by replacing ? in the right places.
    if (other.isTopFunction()) {
      return true;
    }
    FunctionTypeBuilder builder = new FunctionTypeBuilder();
    for (int i = 0; i < other.requiredFormals.size(); i++) {
      JSType formalType = other.getFormalType(i);
      builder.addReqFormal(formalType.isUnknown() ? JSType.BOTTOM : formalType);
    }
    for (int i = 0; i < other.optionalFormals.size(); i++) {
      JSType formalType = other.getFormalType(i);
      builder.addOptFormal(formalType.isUnknown() ? JSType.BOTTOM : formalType);
    }
    if (other.restFormals != null) {
      JSType formalType = other.restFormals;
      builder.addOptFormal(formalType.isUnknown() ? JSType.BOTTOM : formalType);
    }
    if (this.getReturnType().isUnknown()) {
      builder.addRetType(JSType.UNKNOWN);
    } else {
      builder.addRetType(other.getReturnType());
    }
    if (other.isLoose()) {
      builder.addLoose();
    }
    FunctionType newOther = builder.buildFunction();
    return newOther.equals(join(this, newOther));
  }

  static FunctionType join(FunctionType f1, FunctionType f2) {
    if (f1 == null) {
      return f2;
    } else if (f2 == null || f2.isBottomFunction() || f1.equals(f2)) {
      return f1;
    } else if (f1.isBottomFunction()) {
      // Can't merge w/ 1st branch, we only want this if they're both non-null.
      return f2;
    } else if (f1.isTopFunction() || f2.isTopFunction()) {
      return TOP_FUNCTION;
    }

    if (f1.isLoose() || f2.isLoose()) {
      return FunctionType.looseJoin(f1, f2);
    }

    FunctionTypeBuilder builder = new FunctionTypeBuilder();
    int maxRequiredArity = Math.max(
        f1.requiredFormals.size(), f2.requiredFormals.size());
    for (int i = 0; i < maxRequiredArity; i++) {
      JSType reqFormal = nullAcceptingMeet(
          f1.getFormalType(i), f2.getFormalType(i));
      builder.addReqFormal(reqFormal);
    }
    int maxTotalArity = Math.max(
        f1.requiredFormals.size() + f1.optionalFormals.size(),
        f2.requiredFormals.size() + f2.optionalFormals.size());
    for (int i = maxRequiredArity; i < maxTotalArity; i++) {
      JSType optFormal = nullAcceptingMeet(
          f1.getFormalType(i), f2.getFormalType(i));
      builder.addOptFormal(optFormal);
    }
    if (f1.restFormals != null && f2.restFormals != null) {
      builder.addRestFormals(
          nullAcceptingMeet(f1.restFormals, f2.restFormals));
    }
    builder.addRetType(JSType.join(f1.returnType, f2.returnType));
    return builder.buildFunction();
  }

  FunctionType specialize(FunctionType other) {
    if (other == null) {
      return null;
    } else if (!this.isLoose() && other.isLoose()) {
      return this;
    } else {
      return FunctionType.meet(this, other);
    }
  }

  static FunctionType meet(FunctionType f1, FunctionType f2) {
    if (f1 == null || f2 == null) {
      return null;
    } else if (f1.isTopFunction()) {
      return f2;
    } else if (f2.isTopFunction() || f1.equals(f2)) {
      return f1;
    }

    // War is peace, freedom is slavery, meet is join
    if (f1.isLoose() || f2.isLoose()) {
      return FunctionType.looseJoin(f1, f2);
    }

    FunctionTypeBuilder builder = new FunctionTypeBuilder();
    int minRequiredArity = Math.min(
        f1.requiredFormals.size(), f2.requiredFormals.size());
    for (int i = 0; i < minRequiredArity; i++) {
      builder.addReqFormal(
          nullAcceptingJoin(f1.getFormalType(i), f2.getFormalType(i)));
    }
    int maxTotalArity = Math.max(
        f1.requiredFormals.size() + f1.optionalFormals.size(),
        f2.requiredFormals.size() + f2.optionalFormals.size());
    for (int i = minRequiredArity; i < maxTotalArity; i++) {
      builder.addOptFormal(nullAcceptingJoin(
          f1.getFormalType(i), f2.getFormalType(i)));
    }
    if (f1.restFormals != null || f2.restFormals != null) {
      builder.addRestFormals(
          nullAcceptingJoin(f1.restFormals, f2.restFormals));
    }
    builder.addRetType(JSType.meet(f1.returnType, f2.returnType));
    return builder.buildFunction();
  }

  // We may consider true subtyping for deferred checks when the formal
  // parameter has a loose function type.
  boolean isLooseSubtypeOf(FunctionType f2) {
    Preconditions.checkState(this.isLoose() || f2.isLoose());
    if (this.isTopFunction() || f2.isTopFunction()) {
      return true;
    }

    int maxRequiredArity = Math.max(
        this.requiredFormals.size(), f2.requiredFormals.size());
    for (int i = 0; i < maxRequiredArity; i++) {
      if (JSType.meet(this.getFormalType(i), f2.getFormalType(i)).isBottom()) {
        return false;
      }
    }
    if (!this.getReturnType().isBottom() &&
        !f2.getReturnType().isBottom() &&
        JSType.meet(this.getReturnType(), f2.getReturnType()).isBottom()) {
      return false;
    }
    return true;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    Preconditions.checkArgument(obj instanceof FunctionType, "obj is: " + obj);
    FunctionType f2 = (FunctionType) obj;
    return Objects.equal(this.requiredFormals, f2.requiredFormals) &&
        Objects.equal(this.optionalFormals, f2.optionalFormals) &&
        Objects.equal(this.restFormals, f2.restFormals) &&
        Objects.equal(this.returnType, f2.returnType);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        requiredFormals, optionalFormals, restFormals, returnType);
  }

  @Override
  public String toString() {
    if (isTopFunction()) {
      return "TOP_FUNCTION" + (isLoose ? " (loose)" : "");
    }
    List<String> formals = Lists.newLinkedList();
    if (klass != null) {
      formals.add("new:" + klass.name);
    }
    for (JSType formal : requiredFormals) {
      formals.add(formal.toString());
    }
    for (JSType formal : optionalFormals) {
      formals.add(formal.toString() + "=");
    }
    if (restFormals != null) {
      formals.add("..." + restFormals.toString());
    }
    String result = "function (" + Joiner.on(", ").join(formals) + ")";
    if (returnType != null) {
      result += ": " + returnType.toString();
    }
    return result + (isLoose ? " (loose)" : "") +
        (outerVarPreconditions.isEmpty() ? "" : "\tFV:" + outerVarPreconditions);
  }
}