package pcontainment.runtime.machine;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;
import lombok.Setter;
import pcontainment.Checker;
import pcontainment.runtime.*;
import pcontainment.runtime.machine.eventhandlers.EventHandler;
import pcontainment.runtime.machine.eventhandlers.EventHandlerReturnReason;

import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;

import lombok.Getter;

public abstract class Machine {
    private static int machineCount = 0;
    private String name;
    @Getter
    private Checker checker;
    @Getter
    private final ConcreteMachineIdentifier id;
    @Getter @Setter
    private boolean started = false;
    @Getter
    private boolean halted = false;
    @Getter
    private final State startState;
    @Getter
    private final List<State> states;
    @Getter
    private BoolExpr blockedOnReceive;
    private final Deque<Observation> trace = new LinkedBlockingDeque<>();
    private final Map<Event, List<EventHandler>> eventHandlers;

    // TODO: handle deferred messages

    public Machine(String name, int instanceId, State startState, List<State> states,
                   Map<Event, List<EventHandler>> handlers) {
        this.name = name;
        this.id = new ConcreteMachineIdentifier(machineCount++);
        this.startState = startState;
        this.states = states;
        this.eventHandlers = handlers;
        this.checker = new Checker();
    }

    public void addHandler(Event e, EventHandler h) {
        if (!eventHandlers.containsKey(e)) {
            eventHandlers.put(e, new ArrayList<>(Collections.singletonList(h)));
        } else {
            eventHandlers.get(e).add(h);
        }
    }

    private void addReceive(Message r) {
        if (trace.size() > 0) {
            trace.getLast().setPartial(false);
        }
        Observation obs = new Observation(r);
        trace.add(obs);
    }

    private void addSend(Message s) {
        if (trace.size() == 0) {
            throw new RuntimeException("Send without corresponding receive");
        }
        trace.getLast().addSend(s);
    }

    public void observeMessage(Message m) {
        if (this.id.equals(m.getTargetId())) {
            System.out.println("receive message " + m.getEvent().toString());
            addReceive(m);
        } else {
            System.out.println("send message " + m.getEvent().toString());
            addSend(m);
        }
    }

    public void encode() {
        if (trace.size() == 0) return;
        Observation o = trace.getFirst();
        if (!o.isStarted()) {
            processEventToCompletion(o.receive);
            o.setStarted(true);
        }
        int sendLen = o.sends.size();
        while (o.getSendIdx() < sendLen) {
            checker.addConcreteSend(o.sends.get(o.getSendIdx()));
            o.setSendIdx(o.getSendIdx() + 1);
        }
        if (!o.isPartial()) {
            checker.noMoreSends();
            trace.removeFirst();
            checker.nextDepth();
            encode();
        }
    }

    public void check() {
        checker.check();
    }

    public Iterable<EventHandler> getHandlersFor (Event e) {
        return eventHandlers.get(e);
    }

    public void processEventToCompletion(Message receive) {
        System.out.println("process event " + receive.getEvent().toString() + " to completion");
        for (Map.Entry<String, Object> pld : receive.payloads.entrySet()) {
            System.out.println("pld " + pld.getKey() + ": " + pld.getValue());
        }
        Message encoded = new Message(receive.getEvent(), receive.getTargetId(),
                checker.encodeConcretePayload(receive.payloads));
        final EventHandlerReturnReason eventRaiseEventHandlerReturnReason =
                new EventHandlerReturnReason.Raise(encoded);
        checker.runOutcomesToCompletion(this, eventRaiseEventHandlerReturnReason);
    }

    @Override
    public String toString() {
        return String.format("%s(%d)", name, id);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        else if (!(obj instanceof Machine)) {
            return false;
        }
        return this.id == (((Machine) obj).id);
    }

    @Override
    public int hashCode() {
        return ((Integer) id.id).hashCode();
    }
}
