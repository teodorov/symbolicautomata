/**
 * SVPAlib
 * automata.sfa
 * Apr 21, 2015
 * @author Loris D'Antoni
 */
package automata.sfa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.sat4j.specs.TimeoutException;

import automata.Automaton;
import automata.Move;
import automata.safa.BooleanExpressionFactory;
import automata.safa.SAFA;
import automata.safa.SAFAInputMove;
import automata.safa.booleanexpression.PositiveBooleanExpression;
import theory.BooleanAlgebra;
import utilities.Block;
import utilities.Pair;
import utilities.UnionFindHopKarp;

/**
 * Symbolic finite automaton
 * 
 * @param
 * 			<P>
 *            set of predicates over the domain S
 * @param <S>
 *            domain of the automaton alphabet
 */
public class SFA<P, S> extends Automaton<P, S> {
	// ------------------------------------------------------
	// Constant automata
	// ------------------------------------------------------

	public void setIsDet(boolean b) {
		isDeterministic = b;
	}

	/**
	 * Returns the empty SFA for the Boolean algebra <code>ba</code>
	 */
	public static <A, B> SFA<A, B> getEmptySFA(BooleanAlgebra<A, B> ba) {
		SFA<A, B> aut = new SFA<A, B>();
		aut.states = new HashSet<Integer>();
		aut.states.add(0);
		aut.finalStates = new HashSet<Integer>();
		aut.initialState = 0;
		aut.isDeterministic = true;
		aut.isEmpty = true;
		aut.isEpsilonFree = true;
		aut.maxStateId = 1;
		aut.addTransition(new SFAInputMove<A, B>(0, 0, ba.True()), ba, true);
		return aut;
	}

	/**
	 * Returns the SFA accepting every string in the Boolean algebra
	 * <code>ba</code>
	 */
	public static <A, B> SFA<A, B> getFullSFA(BooleanAlgebra<A, B> ba) {
		SFA<A, B> aut = new SFA<A, B>();
		aut.states = new HashSet<Integer>();
		aut.states.add(0);
		aut.finalStates = new HashSet<Integer>(aut.states);
		aut.initialState = 0;
		aut.isDeterministic = true;
		aut.isEmpty = false;
		aut.isEpsilonFree = true;
		aut.maxStateId = 1;
		aut.addTransition(new SFAInputMove<A, B>(0, 0, ba.True()), ba, true);
		return aut;
	}

	// ------------------------------------------------------
	// Automata properties
	// ------------------------------------------------------

	private Integer initialState;
	private Collection<Integer> states;
	private Collection<Integer> finalStates;

	protected Map<Integer, Collection<SFAInputMove<P, S>>> inputMovesFrom;
	protected Map<Integer, Collection<SFAInputMove<P, S>>> inputMovesTo;
	protected Map<Integer, Collection<SFAEpsilon<P, S>>> epsilonFrom;
	protected Map<Integer, Collection<SFAEpsilon<P, S>>> epsilonTo;

	private Integer maxStateId;
	private Integer transitionCount;

	/**
	 * @return the maximum state id
	 */
	public Integer getMaxStateId() {
		return maxStateId;
	}

	/**
	 * @return number of states in the automaton
	 */
	public Integer stateCount() {
		return states.size();
	}

	/**
	 * @return number of transitions in the automaton
	 */
	public Integer getTransitionCount() {
		return transitionCount;
	}

	// ------------------------------------------------------
	// Constructors
	// ------------------------------------------------------

	// Initializes all the fields of the automaton
	private SFA() {
		super();
		finalStates = new HashSet<Integer>();
		states = new HashSet<Integer>();
		inputMovesFrom = new HashMap<Integer, Collection<SFAInputMove<P, S>>>();
		inputMovesTo = new HashMap<Integer, Collection<SFAInputMove<P, S>>>();
		epsilonFrom = new HashMap<Integer, Collection<SFAEpsilon<P, S>>>();
		epsilonTo = new HashMap<Integer, Collection<SFAEpsilon<P, S>>>();
		transitionCount = 0;
		maxStateId = 0;
	}

	/**
	 * Create an automaton and removes unreachable states
	 * 
	 * @throws TimeoutException
	 */
	public static <A, B> SFA<A, B> MkSFA(Collection<SFAMove<A, B>> transitions, Integer initialState,
			Collection<Integer> finalStates, BooleanAlgebra<A, B> ba) throws TimeoutException {

		return MkSFA(transitions, initialState, finalStates, ba, true);
	}

	/**
	 * Create an automaton and removes unreachable states and only removes
	 * unreachable states if <code>remUnreachableStates<code> is true
	 * 
	 * @throws TimeoutException
	 */
	public static <A, B> SFA<A, B> MkSFA(Collection<SFAMove<A, B>> transitions, Integer initialState,
			Collection<Integer> finalStates, BooleanAlgebra<A, B> ba, boolean remUnreachableStates)
					throws TimeoutException {

		return MkSFA(transitions, initialState, finalStates, ba, remUnreachableStates, true);
	}

	/*
	 * Create an automaton and removes unreachable states and only removes
	 * unreachable states if remUnreachableStates is true and normalizes the
	 * automaton if normalize is true
	 */
	private static <A, B> SFA<A, B> MkSFA(Collection<SFAMove<A, B>> transitions, Integer initialState,
			Collection<Integer> finalStates, BooleanAlgebra<A, B> ba, boolean remUnreachableStates, boolean normalize)
					throws TimeoutException {

		SFA<A, B> aut = new SFA<A, B>();

		aut.states = new HashSet<Integer>();
		aut.states.add(initialState);
		aut.states.addAll(finalStates);

		aut.initialState = initialState;
		aut.finalStates = finalStates;
		if (finalStates.isEmpty())
			return getEmptySFA(ba);

		for (SFAMove<A, B> t : transitions)
			aut.addTransition(t, ba, false);

		if (normalize)
			aut = aut.normalize(ba);

		if (remUnreachableStates)
			aut = removeDeadOrUnreachableStates(aut, ba);

		if (aut.finalStates.isEmpty())
			return getEmptySFA(ba);

		return aut;
	}

	// Adds a transition to the SFA
	private void addTransition(SFAMove<P, S> transition, BooleanAlgebra<P, S> ba, boolean skipSatCheck) {

		if (transition.isEpsilonTransition()) {
			if (transition.to == transition.from)
				return;
			isEpsilonFree = false;
		}

		if (skipSatCheck || transition.isSatisfiable(ba)) {

			transitionCount++;

			if (transition.from > maxStateId)
				maxStateId = transition.from;
			if (transition.to > maxStateId)
				maxStateId = transition.to;

			states.add(transition.from);
			states.add(transition.to);

			if (!transition.isEpsilonTransition()) {
				getInputMovesFrom(transition.from).add((SFAInputMove<P, S>) transition);
				getInputMovesTo(transition.to).add((SFAInputMove<P, S>) transition);
			} else {
				getEpsilonFrom(transition.from).add((SFAEpsilon<P, S>) transition);
				getEpsilonTo(transition.to).add((SFAEpsilon<P, S>) transition);
			}
		}
	}

	// ------------------------------------------------------
	// Boolean automata operations
	// ------------------------------------------------------

	/**
	 * Computes the intersection with <code>aut</code> as a new SFA
	 * 
	 * @throws TimeoutException
	 */
	public SFA<P, S> intersectionWith(SFA<P, S> aut, BooleanAlgebra<P, S> ba, long timeout) throws TimeoutException {
		return intersection(this, aut, ba, timeout);
	}

	
	/**
	 * Computes the intersection with <code>aut</code> as a new SFA
	 * 
	 * @throws TimeoutException
	 */
	public SFA<P, S> intersectionWith(SFA<P, S> aut, BooleanAlgebra<P, S> ba) throws TimeoutException {
		return intersection(this, aut, ba, Long.MAX_VALUE);
	}

	/**
	 * Computes the intersection with <code>aut1</code> and <code>aut2</code> as
	 * a new SFA
	 * 
	 * @throws TimeoutException
	 */
	public static <A, B> SFA<A, B> intersection(SFA<A, B> aut1, SFA<A, B> aut2, BooleanAlgebra<A, B> ba, long timeout)
			throws TimeoutException {

		long startTime = System.currentTimeMillis(); 
		
		// if one of the automata is empty return the empty SFA
		if (aut1.isEmpty || aut2.isEmpty)
			return getEmptySFA(ba);

		// components of new SFA
		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState = 0;
		Collection<Integer> finalStates = new ArrayList<Integer>();

		// reached contains the product states (p1,p2) we discovered and maps
		// them to a stateId
		HashMap<Pair<Integer, Integer>, Integer> reached = new HashMap<Pair<Integer, Integer>, Integer>();
		// toVisit contains the product states we still have not explored
		LinkedList<Pair<Integer, Integer>> toVisit = new LinkedList<Pair<Integer, Integer>>();

		// The initial state is the pair consisting of the initial
		// states of aut1 and aut2
		Pair<Integer, Integer> initPair = new Pair<Integer, Integer>(aut1.initialState, aut2.initialState);
		reached.put(initPair, 0);
		toVisit.add(initPair);

		// Explore the product automaton until no new states can be reached
		while (!toVisit.isEmpty()) {
			
			Pair<Integer, Integer> currentState = toVisit.removeFirst();
			int currentStateID = reached.get(currentState);

			// get the set of states reachable from currentState via epsilon
			// moves
			Collection<Integer> epsilonClosure1 = aut1.getEpsClosure(currentState.first, ba);
			Collection<Integer> epsilonClosure2 = aut2.getEpsClosure(currentState.second, ba);

			// if both the epsilon closures contain a final state currentStateID
			// is final
			if (aut1.isFinalConfiguration(epsilonClosure1) && aut2.isFinalConfiguration(epsilonClosure2))
				finalStates.add(currentStateID);

			// Try to pair transitions out of both automata
			for (SFAInputMove<A, B> ct1 : aut1.getInputMovesFrom(epsilonClosure1))
				for (SFAInputMove<A, B> ct2 : aut2.getInputMovesFrom(epsilonClosure2)) {

					if(System.currentTimeMillis()-startTime > timeout)
						throw new TimeoutException();
					
					// create conjunction of the two guards and create
					// transition only if the conjunction is satisfiable
					A intersGuard = ba.MkAnd(ct1.guard, ct2.guard);
					if (ba.IsSatisfiable(intersGuard)) {

						// Create new product transition and add it to
						// transitions
						Pair<Integer, Integer> nextState = new Pair<Integer, Integer>(ct1.to, ct2.to);

						int nextStateId = getStateId(nextState, reached, toVisit);

						SFAInputMove<A, B> newTrans = new SFAInputMove<A, B>(currentStateID, nextStateId, intersGuard);

						transitions.add(newTrans);
					}

				}
		}

		return MkSFA(transitions, initialState, finalStates, ba);
	}

	/**
	 * Computes <code>this</code> minus <code>aut</code> as a new SFA
	 * 
	 * @throws TimeoutException
	 */
	public SFA<P, S> minus(SFA<P, S> aut, BooleanAlgebra<P, S> ba) throws TimeoutException {
		return difference(this, aut, ba, Long.MAX_VALUE);
	}
	
	/**
	 * Computes <code>this</code> minus <code>aut</code> as a new SFA
	 * 
	 * @throws TimeoutException
	 */
	public SFA<P, S> minus(SFA<P, S> aut, BooleanAlgebra<P, S> ba, long timeout) throws TimeoutException {
		return difference(this, aut, ba, timeout);
	}

	/**
	 * Computes <code>aut1</code> minus <code>aut2</code> as a new SFA
	 * 
	 * @throws TimeoutException
	 */
	public static <A, B> SFA<A, B> difference(SFA<A, B> aut1, SFA<A, B> aut2, BooleanAlgebra<A, B> ba, long timeout)
			throws TimeoutException {
		long startTime = System.currentTimeMillis();
		SFA<A, B> compAut2 = aut2.complement(ba,timeout);
		return aut1.intersectionWith(compAut2, ba, timeout - (System.currentTimeMillis()-startTime));
	}

	/**
	 * Computes the union with <code>aut</code> as a new SFA
	 * 
	 * @throws TimeoutException
	 */
	public SFA<P, S> unionWith(SFA<P, S> aut1, BooleanAlgebra<P, S> ba) throws TimeoutException {
		return union(this, aut1, ba);
	}

	/**
	 * Computes the union of <code>aut1</code> and <code>aut2</code> as a new
	 * SFA
	 * 
	 * @throws TimeoutException
	 */
	public static <A, B> SFA<A, B> union(SFA<A, B> aut1, SFA<A, B> aut2, BooleanAlgebra<A, B> ba)
			throws TimeoutException {

		// if both automata are empty return the empty SFA
		if (aut1.isEmpty && aut2.isEmpty)
			return getEmptySFA(ba);

		// components of new SFA
		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState;
		Collection<Integer> finalStates = new ArrayList<Integer>();

		// Offset will be add to all states of aut2
		// to ensure that the states of aut1 and aut2 are disjoint
		int offSet = aut1.maxStateId + 2;

		// Copy the moves of aut1 in transitions
		for (SFAMove<A, B> t : aut1.getTransitions()) {
			@SuppressWarnings("unchecked")
			SFAMove<A, B> newMove = (SFAMove<A, B>) t.clone();
			transitions.add(newMove);
		}

		// Copy the moves of aut2 in transitions
		// and shift the states by offset
		for (SFAMove<A, B> t : aut2.getTransitions()) {
			@SuppressWarnings("unchecked")
			SFAMove<A, B> newMove = (SFAMove<A, B>) t.clone();
			newMove.from += offSet;
			newMove.to += offSet;
			transitions.add(newMove);
		}

		// the new initial state is the first available id
		initialState = aut2.maxStateId + offSet + 1;

		// Add transitions from new initial state to
		// the the initial state of aut1 and
		// the initial state of aut2 shifted by offset
		transitions.add(new SFAEpsilon<A, B>(initialState, aut1.initialState));
		transitions.add(new SFAEpsilon<A, B>(initialState, aut2.initialState + offSet));

		// Make all states of the two machines final
		finalStates.addAll(aut1.finalStates);

		// make all state of aut2 final after adding the offsett
		for (Integer state : aut2.finalStates)
			finalStates.add(state + offSet);

		return MkSFA(transitions, initialState, finalStates, ba, false);
	}

	/**
	 * @return the complement automaton as a new SFA
	 * @throws TimeoutException
	 */
	public SFA<P, S> complement(BooleanAlgebra<P, S> ba) throws TimeoutException {
		return complementOf(this, ba, Long.MAX_VALUE);
	}
	
	/**
	 * @return the complement automaton as a new SFA
	 * @throws TimeoutException
	 */
	public SFA<P, S> complement(BooleanAlgebra<P, S> ba, long timeout) throws TimeoutException {
		return complementOf(this, ba, timeout);
	}

	/**
	 * @return the complement of <code>aut</code> as a new SFA
	 * @throws TimeoutException
	 */
	public static <A, B> SFA<A, B> complementOf(SFA<A, B> aut, BooleanAlgebra<A, B> ba, long timeout) throws TimeoutException {
				
		// make aut total to make sure it has a sink state
		SFA<A, B> autTotal = aut.mkTotal(ba, timeout);
		
		// the final states of the complement are
		// autTotal.states minus autTotal.finalStates
		Collection<Integer> newFinalStates = new HashSet<Integer>();
		for (Integer st : autTotal.states)
			if (!autTotal.finalStates.contains(st))
				newFinalStates.add(st);

		return MkSFA(autTotal.getTransitions(), autTotal.initialState, newFinalStates, ba, false);
	}

	// ------------------------------------------------------
	// Other automata operations
	// ------------------------------------------------------
	/**
	 * @return an equivalent copy without epsilon moves
	 * @throws TimeoutException
	 */
	public SFA<P, S> removeEpsilonMoves(BooleanAlgebra<P, S> ba) throws TimeoutException {
		return removeEpsilonMovesFrom(this, ba);
	}

	/**
	 * @return an equivalent copy without epsilon moves
	 * @throws TimeoutException
	 */
	@SuppressWarnings("unchecked")
	public static <A, B> SFA<A, B> removeEpsilonMovesFrom(SFA<A, B> aut, BooleanAlgebra<A, B> ba)
			throws TimeoutException {

		if (aut.isEpsilonFree)
			return (SFA<A, B>) aut.clone();

		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState = 0;
		Collection<Integer> finalStates = new ArrayList<Integer>();

		HashMap<Collection<Integer>, Integer> reachedStates = new HashMap<Collection<Integer>, Integer>();
		LinkedList<Collection<Integer>> toVisitStates = new LinkedList<Collection<Integer>>();

		// Add initial state
		Collection<Integer> reachableFromInit = aut.getEpsClosure(aut.initialState, ba);

		reachedStates.put(reachableFromInit, 0);
		toVisitStates.add(reachableFromInit);

		while (!toVisitStates.isEmpty()) {
			Collection<Integer> currState = toVisitStates.removeFirst();
			int currStateId = reachedStates.get(currState);

			for (SFAMove<A, B> t1 : aut.getTransitionsFrom(currState)) {
				if (!t1.isEpsilonTransition()) {
					Collection<Integer> nextState = aut.getEpsClosure(t1.to, ba);

					int nextStateId = 0;

					if (!reachedStates.containsKey(nextState)) {
						int index = reachedStates.size();
						reachedStates.put(nextState, index);
						toVisitStates.add(nextState);
						nextStateId = index;
					} else {
						nextStateId = reachedStates.get(nextState);
					}

					SFAMove<A, B> tnew = (SFAMove<A, B>) t1.clone();
					tnew.from = currStateId;
					tnew.to = nextStateId;

					transitions.add(tnew);
				}
			}

		}

		for (Collection<Integer> stSet : reachedStates.keySet())
			if (aut.isFinalConfiguration(stSet))
				finalStates.add(reachedStates.get(stSet));

		return MkSFA(transitions, initialState, finalStates, ba, false);
	}
	
	/**
	 * @return a new total equivalent total SFA (with one transition for each
	 *         symbol out of every state)
	 * @throws TimeoutException
	 */
	public SFA<P, S> mkTotal(BooleanAlgebra<P, S> ba) throws TimeoutException {
		return mkTotal(this, ba, Long.MAX_VALUE);
	}

	/**
	 * @return a new total equivalent total SFA (with one transition for each
	 *         symbol out of every state)
	 * @throws TimeoutException
	 */
	public SFA<P, S> mkTotal(BooleanAlgebra<P, S> ba, long timeout) throws TimeoutException {
		return mkTotal(this, ba, timeout);
	}

	/**
	 * @return a new total total SFA (with one transition for each symbol out of
	 *         every state) equivalent to <code>aut</code>
	 * @throws TimeoutException
	 */
	@SuppressWarnings("unchecked")
	public static <A, B> SFA<A, B> mkTotal(SFA<A, B> aut, BooleanAlgebra<A, B> ba, long timeout) throws TimeoutException {

		if (aut.isTotal) {
			return (SFA<A, B>) aut.clone();
		}

		long startTime = System.currentTimeMillis();
		
		SFA<A, B> sfa = aut;
		if (!aut.isDeterministic(ba))
			sfa = determinize(aut, ba, timeout);

		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState = sfa.initialState;
		Collection<Integer> finalStates = new HashSet<Integer>(sfa.finalStates);

		int sinkState = sfa.maxStateId + 1;
		boolean addSink = false;
		for (Integer state : sfa.states) {
			if(System.currentTimeMillis()-startTime>timeout)
				throw new TimeoutException();
			
			A totGuard = null;
			for (SFAInputMove<A, B> move : sfa.getInputMovesFrom(state)) {
				transitions.add(move);
				if (totGuard == null)
					totGuard = ba.MkNot(move.guard);
				else
					totGuard = ba.MkAnd(totGuard, ba.MkNot(move.guard));
			}
			// If there are not transitions out of the state set the guard to
			// the sink to true
			if (totGuard == null)
				totGuard = ba.True();
			if (ba.IsSatisfiable(totGuard)) {
				addSink = true;
				transitions.add(new SFAInputMove<A, B>(state, sinkState, totGuard));
			}
		}
		if (addSink)
			transitions.add(new SFAInputMove<A, B>(sinkState, sinkState, ba.True()));

		// Do not remove unreachable states otherwise the sink will be removed
		// again
		return MkSFA(transitions, initialState, finalStates, ba, false);
	}

	/**
	 * checks whether the aut accepts the same language
	 * 
	 * @throws TimeoutException
	 */
	public boolean isEquivalentTo(SFA<P, S> aut, BooleanAlgebra<P, S> ba) throws TimeoutException {
		return areEquivalent(this, aut, ba);
	}

	/**
	 * checks whether aut1 is equivalent to aut2
	 * 
	 * @throws TimeoutException
	 */
	public static <A, B> boolean areEquivalent(SFA<A, B> aut1, SFA<A, B> aut2, BooleanAlgebra<A, B> ba)
			throws TimeoutException {
		return areEquivalent(aut1, aut2, ba, Long.MAX_VALUE);
	}
	
	/**
	 * checks whether aut1 is equivalent to aut2
	 * 
	 * @throws TimeoutException
	 */
	public static <A, B> boolean areEquivalent(SFA<A, B> aut1, SFA<A, B> aut2, BooleanAlgebra<A, B> ba, long timeout)
			throws TimeoutException {
		long startTime = System.currentTimeMillis();
		if (!difference(aut1, aut2, ba, timeout).isEmpty)
			return false;
		return difference(aut2, aut1, ba, timeout-(System.currentTimeMillis()-startTime)).isEmpty;
	}

	/**
	 * checks whether the aut accepts the same language
	 * 
	 * @throws TimeoutException
	 */
	public Pair<Boolean, List<S>> isHopcroftKarpEquivalentTo(SFA<P, S> aut, BooleanAlgebra<P, S> ba)
			throws TimeoutException {
		return areHopcroftKarpEquivalent(this.determinize(ba).mkTotal(ba).normalize(ba),
				aut.determinize(ba).mkTotal(ba).normalize(ba), ba);
	}

	/**
	 * checks whether aut1 is equivalent to aut2 using Hopcroft Karp's algorithm
	 * 
	 * @throws TimeoutException
	 */
	public static <A, B> Pair<Boolean, List<B>> areHopcroftKarpEquivalent(SFA<A, B> aut1, SFA<A, B> aut2,
			BooleanAlgebra<A, B> ba) throws TimeoutException {

		UnionFindHopKarp<B> ds = new UnionFindHopKarp<>();
		int offset = aut1.stateCount();

		ds.add(aut1.initialState, aut1.isFinalState(aut1.initialState), new LinkedList<>());
		ds.add(aut2.initialState + offset, aut2.isFinalState(aut2.initialState), new LinkedList<>());
		ds.mergeSets(aut1.initialState, aut2.initialState + offset);

		LinkedList<Pair<Integer, Integer>> toVisit = new LinkedList<>();
		toVisit.add(new Pair<Integer, Integer>(aut1.initialState, aut2.initialState));
		while (!toVisit.isEmpty()) {
			Pair<Integer, Integer> curr = toVisit.removeFirst();
			for (SFAInputMove<A, B> move1 : aut1.getInputMovesFrom(curr.first))
				for (SFAInputMove<A, B> move2 : aut2.getInputMovesFrom(curr.second)) {
					A conj = ba.MkAnd(move1.guard, move2.guard);
					if (ba.IsSatisfiable(conj)) {
						int r1 = move1.to;
						int r2 = move2.to + offset;

						List<B> pref = new LinkedList<B>(ds.getWitness(curr.first));
						pref.add(ba.generateWitness(conj));

						if (!ds.contains(r1))
							ds.add(r1, aut1.isFinalState(move1.to), pref);
						if (!ds.contains(r2))
							ds.add(r2, aut2.isFinalState(move2.to), pref);

						if (!ds.areInSameSet(r1, r2)) {
							if (!ds.mergeSets(r1, r2))
								return new Pair<Boolean, List<B>>(false, pref);
							toVisit.add(new Pair<Integer, Integer>(move1.to, move2.to));
						}
					}
				}
		}

		return new Pair<Boolean, List<B>>(true, null);
	}

	/**
	 * concatenation with aut
	 * 
	 * @throws TimeoutException
	 */
	public SFA<P, S> concatenateWith(SFA<P, S> aut, BooleanAlgebra<P, S> ba) throws TimeoutException {
		return concatenate(this, aut, ba);
	}

	/**
	 * concatenates aut1 with aut2
	 * 
	 * @throws TimeoutException
	 */
	@SuppressWarnings("unchecked")
	public static <A, B> SFA<A, B> concatenate(SFA<A, B> aut1, SFA<A, B> aut2, BooleanAlgebra<A, B> ba)
			throws TimeoutException {

		if (aut1.isEmpty || aut2.isEmpty)
			return getEmptySFA(ba);

		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState = aut1.initialState;
		Collection<Integer> finalStates = new HashSet<Integer>();

		int offSet = aut1.maxStateId + 1;

		for (SFAMove<A, B> t : aut1.getTransitions())
			transitions.add((SFAMove<A, B>) t.clone());

		for (SFAMove<A, B> t : aut2.getTransitions()) {
			SFAMove<A, B> newMove = (SFAMove<A, B>) t.clone();
			newMove.from += offSet;
			newMove.to += offSet;
			transitions.add(newMove);
		}

		for (Integer state1 : aut1.finalStates)
			transitions.add(new SFAEpsilon<A, B>(state1, aut2.initialState + offSet));

		for (Integer state : aut2.finalStates)
			finalStates.add(state + offSet);

		return MkSFA(transitions, initialState, finalStates, ba, false);
	}

	/**
	 * language star
	 * 
	 * @throws TimeoutException
	 */
	@SuppressWarnings("unchecked")
	public static <A, B> SFA<A, B> star(SFA<A, B> aut, BooleanAlgebra<A, B> ba) throws TimeoutException {

		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState = 0;
		Collection<Integer> finalStates = new HashSet<Integer>();

		initialState = aut.maxStateId + 1;

		for (SFAMove<A, B> t : aut.getTransitions())
			transitions.add((SFAMove<A, B>) t.clone());

		// add eps transition from finalStates to initial state
		for (Integer finState : aut.finalStates)
			transitions.add(new SFAEpsilon<A, B>(finState, initialState));

		// add eps transition from new initial state to old initial state
		transitions.add(new SFAEpsilon<A, B>(initialState, aut.initialState));

		// The only final state is the new initial state
		finalStates.add(initialState);

		return MkSFA(transitions, initialState, finalStates, ba, false);
	}

	/**
	 * @return an equivalent deterministic SFA
	 * @throws TimeoutException
	 */
	public SFA<P, S> determinize(BooleanAlgebra<P, S> ba) throws TimeoutException {
		return determinize(this, ba, Long.MAX_VALUE);
	}
	
	/**
	 * @return an equivalent deterministic SFA
	 * @throws TimeoutException
	 */
	public SFA<P, S> determinize(BooleanAlgebra<P, S> ba, long timeout) throws TimeoutException {
		return determinize(this, ba, timeout);
	}

	/**
	 * @return a deterministic SFA that is equivalent to <code>aut</code>
	 * @throws TimeoutException
	 */
	public static <A, B> SFA<A, B> determinize(SFA<A, B> aut, BooleanAlgebra<A, B> ba, long timeout) throws TimeoutException {

		long startTime = System.currentTimeMillis();
		
		if (aut.isDeterministic(ba))
			return aut;

		// Remove epsilon moves before starting
		SFA<A, B> autChecked = aut;
		if (!aut.isEpsilonFree)
			autChecked = aut.removeEpsilonMoves(ba);

		// components of new SFA
		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState = 0;
		Collection<Integer> finalStates = new HashSet<Integer>();

		// reached contains the subset states we discovered and maps them to a
		// stateId
		HashMap<Collection<Integer>, Integer> reachedStates = new HashMap<Collection<Integer>, Integer>();
		// toVisit contains the subset states we still have not explored
		LinkedList<Collection<Integer>> toVisitStates = new LinkedList<Collection<Integer>>();

		// the initial state is the set {aut.initialState}
		Collection<Integer> detInitialState = new HashSet<Integer>();
		detInitialState.add(initialState);
		initialState = 0;

		reachedStates.put(detInitialState, 0);
		toVisitStates.add(detInitialState);

		// Explore the automaton until no new subset states can be reached
		while (!toVisitStates.isEmpty()) {

			if(System.currentTimeMillis()-startTime>timeout)
				throw new TimeoutException();
			
			Collection<Integer> currentState = toVisitStates.removeFirst();
			int currentStateId = reachedStates.get(currentState);

			// check if final
			if (autChecked.isFinalConfiguration(currentState))
				finalStates.add(currentStateId);

			// get all the moves out of the states in the current subset
			ArrayList<SFAInputMove<A, B>> movesFromCurrState = new ArrayList<SFAInputMove<A, B>>(
					autChecked.getInputMovesFrom(currentState));

			// put in a separate list all the predicates of the moves and in the
			// same order. We will use them to build the minterms
			ArrayList<A> predicatesOfMoves = new ArrayList<A>();
			for (SFAInputMove<A, B> inter : movesFromCurrState)
				predicatesOfMoves.add(inter.guard);

			// build the minterms using the predicates and iterate over them:
			// each minterm is a predicate together with the the corresponding
			// set of transition IDs
			for (Pair<A, ArrayList<Integer>> minterm : ba.GetMinterms(predicatesOfMoves, timeout- (System.currentTimeMillis()-startTime))) {

				if(System.currentTimeMillis()-startTime>timeout)
					throw new TimeoutException();
				
				A guard = minterm.first;

				// The new state contains all the target states of the moves
				// with bit 1
				ArrayList<Integer> moveBits = minterm.second;
				Collection<Integer> toState = new HashSet<Integer>();
				for (int moveIndex = 0; moveIndex < moveBits.size(); moveIndex++)
					if (moveBits.get(moveIndex) == 1)
						// add the target state of the moveIndex-th move in the
						// list
						toState.add(movesFromCurrState.get(moveIndex).to);

				// Add new move if target state is not the empty set
				if (toState.size() > 0) {
					int toStateId = getStateId(toState, reachedStates, toVisitStates);
					transitions.add(new SFAInputMove<A, B>(currentStateId, toStateId, guard));
				}
			}
		}

		SFA<A, B> determinized = MkSFA(transitions, initialState, finalStates, ba, false);
		// set isDetermistic to true to avoid future redundancy
		determinized.isDeterministic = true;
		return determinized;
	}

	/**
	 * Creates a normalized copy of the SFA where all transitions between states
	 * are collapsed taking their union, and states are renamed with 0,1,...
	 * 
	 * @throws TimeoutException
	 */
	public SFA<P, S> normalize(BooleanAlgebra<P, S> ba) throws TimeoutException {
		return getNormalized(this, ba);
	}

	/**
	 * Creates a normalized copy of <code>aut<code> where all transitions
	 * between states are collapsed taking their union
	 * 
	 * @throws TimeoutException
	 */
	public static <A, B> SFA<A, B> getNormalized(SFA<A, B> aut, BooleanAlgebra<A, B> ba) throws TimeoutException {

		if (aut.isEmpty)
			return getEmptySFA(ba);

		// components of new SFA
		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState = aut.initialState;
		Collection<Integer> finalStates = new HashSet<Integer>(aut.finalStates);

		// New moves
		Map<Pair<Integer, Integer>, A> inputMoves = new HashMap<Pair<Integer, Integer>, A>();
		Set<Pair<Integer, Integer>> epsMoves = new HashSet<Pair<Integer, Integer>>();

		// Create disjunction of all rules between same state
		for (SFAInputMove<A, B> move : aut.getInputMovesFrom(aut.states)) {
			Pair<Integer, Integer> fromTo = new Pair<Integer, Integer>(move.from, move.to);
			if (inputMoves.containsKey(fromTo))
				inputMoves.put(fromTo, ba.MkOr(move.guard, inputMoves.get(fromTo)));
			else
				inputMoves.put(fromTo, move.guard);
		}
		// Keep at most one epsilon move between every two state
		for (SFAEpsilon<A, B> move : aut.getEpsilonFrom(aut.states)) {
			Pair<Integer, Integer> fromTo = new Pair<Integer, Integer>(move.from, move.to);
			epsMoves.add(fromTo);
		}

		// Create the new transition function
		for (Pair<Integer, Integer> p : inputMoves.keySet())
			transitions.add(new SFAInputMove<A, B>(p.first, p.second, inputMoves.get(p)));
		for (Pair<Integer, Integer> p : epsMoves)
			transitions.add(new SFAEpsilon<A, B>(p.first, p.second));

		return MkSFA(transitions, initialState, finalStates, ba, false, false);
	}

	/**
	 * @return a minimized copy of the SFA
	 * @throws TimeoutException
	 */
	public SFA<P, S> minimize(BooleanAlgebra<P, S> ba) throws TimeoutException {
		return getMinimalOf(this, ba);
	}

	/**
	 * @return a minimized copy of <code>aut<code>
	 * @throws TimeoutException
	 */
	public static <A, B> SFA<A, B> getMinimalOf(SFA<A, B> aut, BooleanAlgebra<A, B> ba) throws TimeoutException {

		if (aut.isEmpty)
			return getEmptySFA(ba);

		SFA<A, B> totalAut = aut;
		if (!aut.isDeterministic)
			totalAut = aut.determinize(ba);

		totalAut = totalAut.mkTotal(ba);

		// This algorithm is presented the POPL14 paper by D'Antoni and Veanes
		// Minimization of symbolic automata

		Collection<Integer> finStates = totalAut.getFinalStates();
		Collection<Integer> nonFinStates = totalAut.getNonFinalStates();

		// Initially split states into final and non-final
		Block fB = new Block(finStates);
		Block nfB = new Block(nonFinStates);

		// stateToBlock remembers for each state the containing block
		Map<Integer, Block> stateToBlock = new HashMap<Integer, Block>();
		for (int q : finStates)
			stateToBlock.put(q, fB);
		for (int q : nonFinStates)
			stateToBlock.put(q, nfB);

		// blocks that might still be split
		Stack<Block> toExploreBlocks = new Stack<Block>();

		// Initialize search stack with the smallest block
		if (nfB.size() < fB.size())
			toExploreBlocks.push(nfB);
		else
			toExploreBlocks.push(fB);

		// Continue until all blocks have been split
		while (!toExploreBlocks.isEmpty()) {
			Block currentBlock = toExploreBlocks.pop();

			// stateToPredIntoCurrentBlock(s) contains the predicate for which
			// a move of s goes into a state in currentBlock
			Map<Integer, A> stateToPredIntoCurrentBlock = new HashMap<Integer, A>();
			for (SFAInputMove<A, B> move : totalAut.getInputMovesTo(currentBlock.set)) {
				if (stateToPredIntoCurrentBlock.containsKey(move.from))
					stateToPredIntoCurrentBlock.put(move.from,
							ba.MkOr(stateToPredIntoCurrentBlock.get(move.from), move.guard));
				else
					stateToPredIntoCurrentBlock.put(move.from, move.guard);
			}

			// Set of states going into currentBlock with some transition
			Block preOfCurrentBlock = new Block(stateToPredIntoCurrentBlock.keySet());

			// Blocks intersecting with preOfCurrentBlock
			HashSet<Block> relevantBlocks = new HashSet<Block>();
			for (int state : stateToBlock.keySet())
				if (preOfCurrentBlock.set.contains(state))
					relevantBlocks.add(stateToBlock.get(state));

			// split relevant blocks
			for (Block relevantBlock : relevantBlocks) {
				Block splitBlock = relevantBlock.intersectWith(preOfCurrentBlock);
				// Change only if the intersection made the block smaller
				if (splitBlock.size() < relevantBlock.size()) {
					for (int p : splitBlock.set) {
						relevantBlock.remove(p);
						stateToBlock.put(p, splitBlock);
					}
					if (toExploreBlocks.contains(relevantBlock))
						toExploreBlocks.push(splitBlock);
					else if (relevantBlock.size() <= splitBlock.size())
						toExploreBlocks.push(relevantBlock);
					else
						toExploreBlocks.push(splitBlock);
				}
			}

			boolean iterate = true;
			while (iterate) {
				iterate = false;

				// Blocks intersecting with preOfCurrentBlock
				relevantBlocks = new HashSet<Block>();
				for (int state : stateToBlock.keySet())
					if (preOfCurrentBlock.set.contains(state))
						relevantBlocks.add(stateToBlock.get(state));

				// split relevant blocks
				for (Block relevantBlock : relevantBlocks) {
					Block splitBlock = new Block();

					int current = relevantBlock.getFirst();
					A psi = stateToPredIntoCurrentBlock.get(current);

					boolean splitterFound = false;
					splitBlock.add(current);

					while (relevantBlock.hasNext()) {
						int q = relevantBlock.getNext();
						A phi = stateToPredIntoCurrentBlock.get(q);
						if (splitterFound) {
							A conj = ba.MkAnd(psi, phi);
							if (ba.IsSatisfiable(conj)) {
								splitBlock.add(q);
								psi = conj;
							}
						} else {
							A conj = ba.MkAnd(psi, ba.MkNot(phi));
							if (ba.IsSatisfiable(conj)) {
								psi = conj; // refine the local minterm
								splitterFound = true;
							} else { // psi implies phi
								conj = ba.MkAnd(phi, ba.MkNot(psi));
								if (ba.IsSatisfiable(conj)) {
									splitBlock.clear();
									splitBlock.add(q);
									psi = conj;
									splitterFound = true;
								} else {
									splitBlock.add(q);
								}
							}
						}
					}
					// Change only if the intersection made the block smaller
					if (splitBlock.size() < relevantBlock.size()) {
						// (a,R)-split of P for some a
						iterate = (iterate || (relevantBlock.size() > 2));
						for (int p : splitBlock.set) {
							relevantBlock.remove(p);
							stateToBlock.put(p, splitBlock);
						}
						if (toExploreBlocks.contains(relevantBlock))
							toExploreBlocks.push(splitBlock);
						else if (relevantBlock.size() <= splitBlock.size())
							toExploreBlocks.push(relevantBlock);
						else
							toExploreBlocks.push(splitBlock);
					}
				}
			}
		}

		// minimal automaton components
		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState = 0;
		Map<Block, Integer> blockToIndex = new HashMap<Block, Integer>();
		Map<Integer, Integer> stateToClass = new HashMap<Integer, Integer>();
		Collection<Integer> finalStates = new HashSet<Integer>();

		// One state per block
		for (int state : totalAut.states) {
			Block b = stateToBlock.get(state);
			if (!blockToIndex.containsKey(b))
				blockToIndex.put(b, blockToIndex.size());
			int eqClass = blockToIndex.get(b);
			stateToClass.put(state, eqClass);
			if (totalAut.isFinalState(state))
				finalStates.add(eqClass);
		}

		initialState = stateToClass.get(totalAut.initialState);

		// Create minimal automaton from equivalence classes
		for (Block b : blockToIndex.keySet()) {
			int st = blockToIndex.get(b);
			for (SFAInputMove<A, B> t : totalAut.getInputMovesFrom(b.set))
				transitions.add(new SFAInputMove<A, B>(st, stateToClass.get(t.to), t.guard));

		}

		return MkSFA(transitions, initialState, finalStates, ba, false, true);
	}

	// ------------------------------------------------------
	// Automata properties
	// ------------------------------------------------------

	/**
	 * Checks whether the SFA is ambiguous
	 * 
	 * @return an ambiguous input if the automaton is ambiguous,
	 *         <code>null</code> otherwise
	 * @throws TimeoutException
	 */
	public List<S> getAmbiguousInput(BooleanAlgebra<P, S> ba) throws TimeoutException {
		return getAmbiguousInput(this, ba);
	}

	/**
	 * Checks whether <code>aut</code> is ambiguous
	 * 
	 * @return an ambiguous input if the automaton is ambiguous,
	 *         <code>null</code> otherwise
	 * @throws TimeoutException
	 */
	@SuppressWarnings("unchecked")
	public static <A, B> List<B> getAmbiguousInput(SFA<A, B> aut, BooleanAlgebra<A, B> ba) throws TimeoutException {

		SFA<A, B> aut1 = (SFA<A, B>) aut.clone();
		SFA<A, B> aut2 = (SFA<A, B>) aut.clone();

		SFA<A, B> product = new SFA<A, B>();

		// maps a product state to its id
		HashMap<Pair<Integer, Integer>, Integer> reached = new HashMap<Pair<Integer, Integer>, Integer>();
		// maps and id to its product state
		HashMap<Integer, Pair<Integer, Integer>> reachedRev = new HashMap<Integer, Pair<Integer, Integer>>();
		// list on unexplored product states
		LinkedList<Pair<Integer, Integer>> toVisit = new LinkedList<Pair<Integer, Integer>>();

		// The initial state is the pair consisting of the initial
		// states of aut1 and aut2
		Pair<Integer, Integer> initStatePair = new Pair<Integer, Integer>(aut1.initialState, aut2.initialState);
		product.initialState = 0;
		product.states.add(0);

		reached.put(initStatePair, 0);
		reachedRev.put(0, initStatePair);
		toVisit.add(initStatePair);

		int totStates = 1;

		while (!toVisit.isEmpty()) {
			Pair<Integer, Integer> currState = toVisit.removeFirst();
			int currStateId = reached.get(currState);

			// get the set of states reachable from currentState via epsilon
			// moves
			Collection<Integer> epsilonClosure1 = aut1.getEpsClosure(currState.first, ba);
			Collection<Integer> epsilonClosure2 = aut2.getEpsClosure(currState.second, ba);

			// Set final states
			// if both the epsilon closures contain a final state currentStateID
			// is final
			if (aut1.isFinalConfiguration(epsilonClosure1) && aut2.isFinalConfiguration(epsilonClosure2))
				product.finalStates.add(currStateId);

			// Try to pair transitions out of both automata
			for (SFAInputMove<A, B> t1 : aut1.getInputMovesFrom(epsilonClosure1))
				for (SFAInputMove<A, B> t2 : aut2.getInputMovesFrom(epsilonClosure2)) {

					// create conjunction of the two guards and create
					// transition only if the conjunction is satisfiable
					A intersGuard = ba.MkAnd(t1.guard, t2.guard);
					if (ba.IsSatisfiable(intersGuard)) {

						// Create new product transition and add it to
						// transitions
						Pair<Integer, Integer> nextState = new Pair<Integer, Integer>(t1.to, t2.to);
						int nextStateId = 0;

						if (!reached.containsKey(nextState)) {
							product.inputMovesTo.put(totStates, new HashSet<SFAInputMove<A, B>>());

							reached.put(nextState, totStates);
							reachedRev.put(totStates, nextState);

							toVisit.add(nextState);
							product.states.add(totStates);
							nextStateId = totStates;
							totStates++;
						} else
							nextStateId = reached.get(nextState);

						SFAInputMove<A, B> newTrans = new SFAInputMove<A, B>(currStateId, nextStateId, intersGuard);

						product.addTransition(newTrans, ba, true);
					}

				}
		}

		product = removeDeadOrUnreachableStates(product, ba);

		// Check if a state that of the form (s1,s2) such that s1!=s2 is still
		// alive, if so any string passing to it is ambiguous
		for (Integer aliveSt : product.states) {
			Pair<Integer, Integer> stP = reachedRev.get(aliveSt);
			if (stP.first != stP.second) {
				SFA<A, B> left = (SFA<A, B>) product.clone();
				SFA<A, B> right = (SFA<A, B>) product.clone();
				left.finalStates = new HashSet<Integer>();
				left.finalStates.add(aliveSt);
				right.initialState = aliveSt;

				SFA<A, B> c = left.concatenateWith(right, ba);
				SFA<A, B> clean = removeDeadOrUnreachableStates(c, ba);
				return clean.getWitness(ba);
			}
		}
		return null;
	}

	
	/**
	 * Returns a SAFA equivalent to the SFA
	 * @param ba
	 * @return
	 * @throws TimeoutException
	 */
	public SAFA<P, S> getSAFA(BooleanAlgebra<P, S> ba) throws TimeoutException {
		
		SFA<P,S> noneps = this.removeEpsilonMoves(ba);
		
		BooleanExpressionFactory<PositiveBooleanExpression> boolexpr = SAFA.getBooleanExpressionFactory();

		PositiveBooleanExpression init = boolexpr.MkState(noneps.initialState);

		Collection<SAFAInputMove<P, S>> moves = new LinkedList<>();
		for(SFAInputMove<P, S> move: noneps.getInputMovesFrom(noneps.states))
			moves.add(new SAFAInputMove<P, S>(move.from, boolexpr.MkState(move.to), move.guard));
		
		Collection<Integer> finalStates = new HashSet<>(noneps.finalStates);

		return SAFA.MkSAFA(moves, init, finalStates, ba, false, true, false);
	}

	/**
	 * Checks whether the automaton is deterministic
	 * 
	 * @return true iff the automaton is deterministic
	 * @throws TimeoutException
	 */
	public boolean isDeterministic(BooleanAlgebra<P, S> ba) throws TimeoutException {
		// Check if we set it before
		if (isDeterministic)
			return true;

		// check only one initial state
		if (!isEpsilonFree) {
			isDeterministic = false;
			return false;
		}

		// Check transitions out of a state are mutually exclusive
		for (Integer state : states) {
			List<SFAMove<P, S>> movesFromState = new ArrayList<SFAMove<P, S>>(getTransitionsFrom(state));

			for (int i = 0; i < movesFromState.size(); i++) {
				SFAMove<P, S> t1 = movesFromState.get(i);
				for (int p = i + 1; p < movesFromState.size(); p++) {
					SFAMove<P, S> t2 = movesFromState.get(p);
					if (!t1.isDisjointFrom(t2, ba)) {
						isDeterministic = false;
						return false;
					}
				}
			}
		}

		isDeterministic = true;
		return true;
	}

	// ------------------------------------------------------
	// Reachability methods
	// ------------------------------------------------------

	// creates a new SFA where all unreachable or dead states have been removed
	private static <A, B> SFA<A, B> removeDeadOrUnreachableStates(SFA<A, B> aut, BooleanAlgebra<A, B> ba)
			throws TimeoutException {

		// components of new SFA
		Collection<SFAMove<A, B>> transitions = new ArrayList<SFAMove<A, B>>();
		Integer initialState = 0;
		Collection<Integer> finalStates = new HashSet<Integer>();

		HashSet<Integer> initStates = new HashSet<Integer>();
		initStates.add(aut.initialState);
		Collection<Integer> reachableFromInit = aut.getReachableStatesFrom(initStates);
		Collection<Integer> reachingFinal = aut.getReachingStates(aut.finalStates);

		Collection<Integer> aliveStates = new HashSet<Integer>();

		// Computes states that reachable from initial state and can reach a
		// final state
		for (Integer state : reachableFromInit)
			if (reachingFinal.contains(state)) {
				aliveStates.add(state);
			}

		if (aliveStates.size() == 0)
			return getEmptySFA(ba);

		for (Integer state : aliveStates)
			for (SFAMove<A, B> t : aut.getTransitionsFrom(state))
				if (aliveStates.contains(t.to))
					transitions.add(t);

		initialState = aut.initialState;

		for (Integer state : aut.finalStates)
			if (aliveStates.contains(state))
				finalStates.add(state);

		return MkSFA(transitions, initialState, finalStates, ba, false, false);
	}

	// Computes states that reachable from states
	private Collection<Integer> getReachableStatesFrom(Collection<Integer> states) {
		HashSet<Integer> result = new HashSet<Integer>();
		for (Integer state : states)
			visitForward(state, result);
		return result;
	}

	// Computes states that can reach states
	private Collection<Integer> getReachingStates(Collection<Integer> states) {
		HashSet<Integer> result = new HashSet<Integer>();
		for (Integer state : states)
			visitBackward(state, result);
		return result;
	}

	// DFS accumulates in reached
	private void visitForward(Integer state, HashSet<Integer> reached) {
		if (!reached.contains(state)) {
			reached.add(state);
			for (SFAMove<P, S> t : this.getTransitionsFrom(state)) {
				Integer nextState = t.to;
				visitForward(nextState, reached);
			}
		}
	}

	// backward DFS accumulates in reached
	private void visitBackward(Integer state, HashSet<Integer> reached) {
		if (!reached.contains(state)) {
			reached.add(state);
			for (SFAMove<P, S> t : this.getTransitionsTo(state)) {
				Integer predState = t.from;
				visitBackward(predState, reached);
			}
		}
	}

	// ------------------------------------------------------
	// Properties accessing methods
	// ------------------------------------------------------

	/**
	 * Returns the set of transitions starting at state <code>s</code>
	 */
	public Collection<SFAMove<P, S>> getTransitionsFrom(Integer state) {
		Collection<SFAMove<P, S>> moves = new HashSet<SFAMove<P, S>>();
		moves.addAll(getInputMovesFrom(state));
		moves.addAll(getEpsilonFrom(state));
		return moves;
	}

	/**
	 * Returns the set of transitions to state <code>s</code>
	 */
	public Collection<SFAMove<P, S>> getTransitionsTo(Integer state) {
		Collection<SFAMove<P, S>> moves = new HashSet<SFAMove<P, S>>();
		moves.addAll(getInputMovesTo(state));
		moves.addAll(getEpsilonTo(state));
		return moves;
	}

	/**
	 * Returns the set of transitions starting set of states
	 */
	public Collection<SFAMove<P, S>> getTransitionsFrom(Collection<Integer> stateSet) {
		Collection<SFAMove<P, S>> transitions = new LinkedList<SFAMove<P, S>>();
		for (Integer state : stateSet)
			transitions.addAll(getTransitionsFrom(state));
		return transitions;
	}

	/**
	 * Returns the set of transitions to a set of states
	 */
	public Collection<SFAMove<P, S>> getTransitionsTo(Collection<Integer> stateSet) {
		Collection<SFAMove<P, S>> transitions = new LinkedList<SFAMove<P, S>>();
		for (Integer state : stateSet)
			transitions.addAll(getTransitionsTo(state));
		return transitions;
	}

	/**
	 * Returns the set of transitions to state <code>s</code>
	 */
	public Collection<SFAEpsilon<P, S>> getEpsilonTo(Integer state) {
		Collection<SFAEpsilon<P, S>> trset = epsilonTo.get(state);
		if (trset == null) {
			trset = new HashSet<SFAEpsilon<P, S>>();
			epsilonTo.put(state, trset);
			return trset;
		}
		return trset;
	}

	/**
	 * Returns the set of transitions starting set of states
	 */
	public Collection<SFAEpsilon<P, S>> getEpsilonTo(Collection<Integer> stateSet) {
		Collection<SFAEpsilon<P, S>> transitions = new LinkedList<SFAEpsilon<P, S>>();
		for (Integer state : stateSet)
			transitions.addAll(getEpsilonTo(state));
		return transitions;
	}

	/**
	 * Returns the set of transitions to state <code>s</code>
	 */
	public Collection<SFAEpsilon<P, S>> getEpsilonFrom(Integer state) {
		Collection<SFAEpsilon<P, S>> trset = epsilonFrom.get(state);
		if (trset == null) {
			trset = new HashSet<SFAEpsilon<P, S>>();
			epsilonFrom.put(state, trset);
			return trset;
		}
		return trset;
	}

	/**
	 * Returns the set of transitions starting set of states
	 */
	public Collection<SFAEpsilon<P, S>> getEpsilonFrom(Collection<Integer> stateSet) {
		Collection<SFAEpsilon<P, S>> transitions = new LinkedList<SFAEpsilon<P, S>>();
		for (Integer state : stateSet)
			transitions.addAll(getEpsilonFrom(state));
		return transitions;
	}

	/**
	 * Returns the set of transitions to state <code>s</code>
	 */
	public Collection<SFAInputMove<P, S>> getInputMovesTo(Integer state) {
		Collection<SFAInputMove<P, S>> trset = inputMovesTo.get(state);
		if (trset == null) {
			trset = new HashSet<SFAInputMove<P, S>>();
			inputMovesTo.put(state, trset);
			return trset;
		}
		return trset;
	}

	/**
	 * Returns the set of transitions starting set of states
	 */
	public Collection<SFAInputMove<P, S>> getInputMovesTo(Collection<Integer> stateSet) {
		Collection<SFAInputMove<P, S>> transitions = new LinkedList<SFAInputMove<P, S>>();
		for (Integer state : stateSet)
			transitions.addAll(getInputMovesTo(state));
		return transitions;
	}

	/**
	 * Returns the set of transitions to state <code>s</code>
	 */
	public Collection<SFAInputMove<P, S>> getInputMovesFrom(Integer state) {
		Collection<SFAInputMove<P, S>> trset = inputMovesFrom.get(state);
		if (trset == null) {
			trset = new HashSet<SFAInputMove<P, S>>();
			inputMovesFrom.put(state, trset);
			return trset;
		}
		return trset;
	}

	/**
	 * Returns the set of transitions starting set of states
	 */
	public Collection<SFAInputMove<P, S>> getInputMovesFrom(Collection<Integer> stateSet) {
		Collection<SFAInputMove<P, S>> transitions = new LinkedList<SFAInputMove<P, S>>();
		for (Integer state : stateSet)
			transitions.addAll(getInputMovesFrom(state));
		return transitions;
	}

	/**
	 * Returns the set of transitions starting set of states
	 */
	public Collection<SFAMove<P, S>> getTransitions() {
		Collection<SFAMove<P, S>> transitions = new LinkedList<SFAMove<P, S>>();
		for (Integer state : states)
			transitions.addAll(getTransitionsFrom(state));
		return transitions;
	}

	// ----------------------------------------------------
	// Overridden methods
	// ----------------------------------------------------

	@Override
	public Collection<Move<P, S>> getMovesFrom(Integer state) {
		Collection<Move<P, S>> transitions = new LinkedList<Move<P, S>>();
		transitions.addAll(getTransitionsFrom(state));
		return transitions;
	}

	@Override
	public Collection<Move<P, S>> getMovesTo(Integer state) {
		Collection<Move<P, S>> transitions = new LinkedList<Move<P, S>>();
		transitions.addAll(getTransitionsTo(state));
		return transitions;
	}

	@Override
	public Integer getInitialState() {
		return initialState;
	}

	@Override
	public Collection<Integer> getFinalStates() {
		return finalStates;
	}

	public Collection<Integer> getNonFinalStates() {
		HashSet<Integer> nonFin = new HashSet<Integer>(states);
		nonFin.removeAll(finalStates);
		return nonFin;
	}

	@Override
	public Collection<Integer> getStates() {
		return states;
	}

	@Override
	public Object clone() {
		SFA<P, S> cl = new SFA<P, S>();

		cl.isDeterministic = isDeterministic;
		cl.isTotal = isTotal;
		cl.isEmpty = isEmpty;
		cl.isEpsilonFree = isEpsilonFree;

		cl.maxStateId = maxStateId;
		cl.transitionCount = transitionCount;

		cl.states = new HashSet<Integer>(states);
		cl.initialState = initialState;
		cl.finalStates = new HashSet<Integer>(finalStates);

		cl.inputMovesFrom = new HashMap<Integer, Collection<SFAInputMove<P, S>>>(inputMovesFrom);
		cl.inputMovesTo = new HashMap<Integer, Collection<SFAInputMove<P, S>>>(inputMovesTo);

		cl.epsilonFrom = new HashMap<Integer, Collection<SFAEpsilon<P, S>>>(epsilonFrom);
		cl.epsilonTo = new HashMap<Integer, Collection<SFAEpsilon<P, S>>>(epsilonTo);

		return cl;
	}

}
