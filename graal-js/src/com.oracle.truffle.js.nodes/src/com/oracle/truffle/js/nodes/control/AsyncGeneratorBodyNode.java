/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.control;

import java.util.ArrayDeque;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.JSReadFrameSlotNode;
import com.oracle.truffle.js.nodes.access.JSWriteFrameSlotNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.arguments.AccessFunctionNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.nodes.function.JSNewNode.SpecializedNewObjectNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.GraalJSException;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JavaScriptRealmBoundaryRootNode;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunction.AsyncGeneratorState;
import com.oracle.truffle.js.runtime.objects.AsyncGeneratorRequest;
import com.oracle.truffle.js.runtime.objects.Completion;
import com.oracle.truffle.js.runtime.objects.Undefined;

public final class AsyncGeneratorBodyNode extends JavaScriptNode {

    @NodeInfo(cost = NodeCost.NONE, language = "JavaScript", description = "The root node of async generator functions in JavaScript.")
    private static final class AsyncGeneratorRootNode extends JavaScriptRealmBoundaryRootNode {
        @Child private PropertyGetNode getGeneratorContext;
        @Child private PropertyGetNode getGeneratorState;
        @Child private PropertySetNode setGeneratorState;
        @Child private JavaScriptNode functionBody;
        @Child private JSWriteFrameSlotNode writeYieldValue;
        @Child private JSReadFrameSlotNode readYieldResult;
        @Child private AsyncGeneratorResolveNode asyncGeneratorResolveNode;
        @Child private AsyncGeneratorRejectNode asyncGeneratorRejectNode;
        private final JSContext context;

        AsyncGeneratorRootNode(JSContext context, JavaScriptNode functionBody, JSWriteFrameSlotNode writeYieldValueNode, JSReadFrameSlotNode readYieldResultNode, SourceSection functionSourceSection) {
            super(context.getLanguage(), functionSourceSection, null);
            this.getGeneratorContext = PropertyGetNode.create(JSFunction.GENERATOR_CONTEXT_ID, false, context);
            this.getGeneratorState = PropertyGetNode.create(JSFunction.GENERATOR_STATE_ID, false, context);
            this.setGeneratorState = PropertySetNode.create(JSFunction.GENERATOR_STATE_ID, false, context, false);
            this.functionBody = functionBody;
            this.writeYieldValue = writeYieldValueNode;
            this.readYieldResult = readYieldResultNode;
            this.context = context;
            this.asyncGeneratorResolveNode = AsyncGeneratorResolveNode.create(context);
            this.asyncGeneratorRejectNode = AsyncGeneratorRejectNode.create(context);
        }

        @Override
        public Object executeAndSetRealm(VirtualFrame frame) {
            DynamicObject generatorObject = (DynamicObject) frame.getArguments()[1];
            Completion completion = (Completion) frame.getArguments()[2];

            VirtualFrame generatorFrame = JSFrameUtil.castMaterializedFrame(getGeneratorContext.getValue(generatorObject));
            AsyncGeneratorState state = (AsyncGeneratorState) getGeneratorState.getValue(generatorObject);

            // State must be Executing when called from AsyncGeneratorResumeNext.
            // State can be Executing or SuspendedYield when resuming from Await.
            assert state == AsyncGeneratorState.Executing || state == AsyncGeneratorState.SuspendedYield : state;
            writeYieldValue.executeWrite(generatorFrame, completion);

            try {
                Object result = functionBody.execute(generatorFrame);
                setGeneratorState.setValue(generatorObject, state = AsyncGeneratorState.Completed);
                asyncGeneratorResolveNode.execute(frame, generatorObject, result, true);
            } catch (YieldException e) {
                if (e.isYield()) {
                    setGeneratorState.setValue(generatorObject, state = AsyncGeneratorState.SuspendedYield);
                    asyncGeneratorResolveNode.execute(frame, generatorObject, e.getResult(), false);
                }
            } catch (GraalJSException e) {
                setGeneratorState.setValue(generatorObject, state = AsyncGeneratorState.Completed);
                Object reason = e.getErrorObjectEager(context);
                asyncGeneratorRejectNode.execute(generatorFrame, generatorObject, reason);
            }
            return Undefined.instance;
        }

        @Override
        protected JSRealm getRealm() {
            return context.getRealm();
        }
    }

    @Child private JavaScriptNode createAsyncGeneratorObject;
    @Child private PropertySetNode setGeneratorState;
    @Child private PropertySetNode setGeneratorContext;
    @Child private PropertySetNode setGeneratorTarget;
    @Child private PropertySetNode setGeneratorQueue;

    @Child private PropertyGetNode getPromise;
    @Child private PropertyGetNode getPromiseReject;
    @Child private JSFunctionCallNode createPromiseCapability;
    @Child private JSFunctionCallNode executePromiseMethod;
    @Child private PropertySetNode setAsyncContext;

    @CompilationFinal RootCallTarget resumeTarget;
    @CompilationFinal DirectCallNode asyncCallNode;
    private final JSContext context;

    @Child private JavaScriptNode functionBody;
    @Child private JSWriteFrameSlotNode writeYieldValueNode;
    @Child private JSReadFrameSlotNode readYieldResultNode;
    @Child private JSWriteFrameSlotNode writeAsyncContext;

    public AsyncGeneratorBodyNode(JSContext context, JavaScriptNode body, JSWriteFrameSlotNode writeYieldValueNode, JSReadFrameSlotNode readYieldResultNode, JSWriteFrameSlotNode writeAsyncContext) {
        this.writeAsyncContext = writeAsyncContext;
        JavaScriptNode functionObject = AccessFunctionNode.create();
        this.createAsyncGeneratorObject = SpecializedNewObjectNode.create(context, false, true, true, functionObject);

        this.setGeneratorState = PropertySetNode.create(JSFunction.GENERATOR_STATE_ID, false, context, false);
        this.setGeneratorContext = PropertySetNode.create(JSFunction.GENERATOR_CONTEXT_ID, false, context, false);
        this.setGeneratorTarget = PropertySetNode.create(JSFunction.GENERATOR_TARGET_ID, false, context, false);
        this.setGeneratorQueue = PropertySetNode.create(JSFunction.ASYNC_GENERATOR_QUEUE_ID, false, context, false);

        this.context = context;

        // these children are adopted here only temporarily; they will be transferred later
        this.functionBody = body;
        this.writeYieldValueNode = writeYieldValueNode;
        this.readYieldResultNode = readYieldResultNode;
    }

    public static JavaScriptNode create(JSContext context, JavaScriptNode body, JSWriteFrameSlotNode writeYieldValueNode, JSReadFrameSlotNode readYieldResultNode,
                    JSWriteFrameSlotNode writeAsyncContext) {
        return new AsyncGeneratorBodyNode(context, body, writeYieldValueNode, readYieldResultNode, writeAsyncContext);
    }

    private void initializeCallTarget() {
        CompilerAsserts.neverPartOfCompilation();
        atomic(() -> {
            AsyncGeneratorRootNode asyncGeneratorRootNode = new AsyncGeneratorRootNode(context, functionBody, writeYieldValueNode, readYieldResultNode, getRootNode().getSourceSection());
            this.resumeTarget = Truffle.getRuntime().createCallTarget(asyncGeneratorRootNode);
            this.asyncCallNode = insert(DirectCallNode.create(resumeTarget));
            // these children have been transferred to the generator root node and are now disowned
            this.functionBody = null;
            this.writeYieldValueNode = null;
            this.readYieldResultNode = null;
        });
    }

    private void ensureCallTargetInitialized() {
        if (resumeTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            initializeCallTarget();
        }
    }

    private void asyncGeneratorStart(VirtualFrame frame, DynamicObject generatorObject) {
        MaterializedFrame materializedFrame = frame.materialize();
        setGeneratorState.setValue(generatorObject, AsyncGeneratorState.SuspendedStart);
        setGeneratorContext.setValue(generatorObject, materializedFrame);
        setGeneratorTarget.setValue(generatorObject, resumeTarget);
        setGeneratorQueue.setValue(generatorObject, new ArrayDeque<AsyncGeneratorRequest>(4));
        writeAsyncContext.executeWrite(frame, new Object[]{resumeTarget, generatorObject, materializedFrame});
    }

    @Override
    public Object execute(VirtualFrame frame) {
        ensureCallTargetInitialized();

        DynamicObject generatorObject;
        try {
            generatorObject = createAsyncGeneratorObject.executeDynamicObject(frame);
        } catch (UnexpectedResultException e) {
            throw Errors.shouldNotReachHere();
        }

        asyncGeneratorStart(frame, generatorObject);

        return generatorObject;
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        if (resumeTarget == null) {
            return create(context, cloneUninitialized(functionBody), cloneUninitialized(writeYieldValueNode), cloneUninitialized(readYieldResultNode), cloneUninitialized(writeAsyncContext));
        } else {
            AsyncGeneratorRootNode generatorRoot = (AsyncGeneratorRootNode) resumeTarget.getRootNode();
            return create(context, cloneUninitialized(generatorRoot.functionBody), cloneUninitialized(generatorRoot.writeYieldValue), cloneUninitialized(generatorRoot.readYieldResult),
                            cloneUninitialized(writeAsyncContext));
        }
    }
}