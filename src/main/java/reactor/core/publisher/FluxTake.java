/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Fuseable;
import reactor.core.Fuseable.ConditionalSubscriber;
import reactor.core.Fuseable.QueueSubscription;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.Trackable;

/**
 * Takes only the first N values from the source Publisher.
 * <p>
 * If N is zero, the subscriber gets completed if the source completes, signals an error or
 * signals its first value (which is not not relayed though).
 *
 * @param <T> the value type
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxTake<T> extends FluxSource<T, T> {

	final long n;

	public FluxTake(Publisher<? extends T> source, long n) {
		super(source);
		if (n < 0) {
			throw new IllegalArgumentException("n >= 0 required but it was " + n);
		}
		this.n = n;
	}

	public Publisher<? extends T> source() {
		return source;
	}

	public long n() {
		return n;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		if (source instanceof Fuseable) {
			source.subscribe(new TakeFuseableSubscriber<>(s, n));
		}
		else {
			if (s instanceof ConditionalSubscriber) {
				source.subscribe(new TakeConditionalSubscriber<>((ConditionalSubscriber<? super T>) s,
						n));
			}
			else {
				source.subscribe(new TakeSubscriber<>(s, n));
			}
		}
	}

	@Override
	public long getPrefetch() {
		return Long.MAX_VALUE;
	}

	static final class TakeSubscriber<T>
			implements Subscriber<T>, Subscription, Receiver, Producer, Trackable {

		final Subscriber<? super T> actual;

		final long n;

		long remaining;

		Subscription s;

		boolean done;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<TakeSubscriber> WIP =
		  AtomicIntegerFieldUpdater.newUpdater(TakeSubscriber.class, "wip");

		public TakeSubscriber(Subscriber<? super T> actual, long n) {
			this.actual = actual;
			this.n = n;
			this.remaining = n;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				actual.onSubscribe(this);
				if (n == 0 && wip == 0) {
					request(Long.MAX_VALUE);
				}
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}

			long r = remaining;

			if (r == 0) {
				s.cancel();
				onComplete();
				return;
			}

			remaining = --r;
			boolean stop = r == 0L;

			actual.onNext(t);

			if (stop) {
				s.cancel();

				onComplete();
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}
			done = true;
			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			actual.onComplete();
		}

		@Override
		public void request(long n) {
			if (wip != 0) {
				s.request(n);
			} else if (WIP.compareAndSet(this, 0, 1)) {
				if (n >= this.n) {
					s.request(Long.MAX_VALUE);
				} else {
					s.request(n);
				}
			}
		}

		@Override
		public void cancel() {
			s.cancel();
		}
		@Override
		public boolean isStarted() {
			return s != null && !done;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public long getCapacity() {
			return n;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public long expectedFromUpstream() {
			return remaining;
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public long limit() {
			return 0;
		}
	}

	static final class TakeConditionalSubscriber<T>
			implements ConditionalSubscriber<T>, Subscription, Receiver, Producer,
			           Trackable {

		final ConditionalSubscriber<? super T> actual;

		final long n;

		long remaining;

		Subscription s;

		boolean done;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<TakeConditionalSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(TakeConditionalSubscriber.class,
						"wip");

		public TakeConditionalSubscriber(ConditionalSubscriber<? super T> actual,
				long n) {
			this.actual = actual;
			this.n = n;
			this.remaining = n;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				actual.onSubscribe(this);
				if (n == 0 && wip == 0) {
					request(Long.MAX_VALUE);
				}
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}

			long r = remaining;

			if (r == 0) {
				s.cancel();
				onComplete();
				return;
			}

			remaining = --r;
			boolean stop = r == 0L;

			actual.onNext(t);

			if (stop) {
				s.cancel();

				onComplete();
			}
		}

		@Override
		public boolean tryOnNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return true;
			}

			long r = remaining;

			if (r == 0) {
				s.cancel();
				onComplete();
				return true;
			}

			remaining = --r;
			boolean stop = r == 0L;

			boolean b = actual.tryOnNext(t);

			if (stop) {
				s.cancel();

				onComplete();
			}
			return b;
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}
			done = true;
			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			actual.onComplete();
		}

		@Override
		public void request(long n) {
			if (wip != 0) {
				s.request(n);
			}
			else if (WIP.compareAndSet(this, 0, 1)) {
				if (n >= this.n) {
					s.request(Long.MAX_VALUE);
				}
				else {
					s.request(n);
				}
			}
		}

		@Override
		public void cancel() {
			s.cancel();
		}
		@Override
		public boolean isStarted() {
			return s != null && !done;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public long getCapacity() {
			return n;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public long expectedFromUpstream() {
			return remaining;
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public long limit() {
			return 0;
		}
	}

	static final class TakeFuseableSubscriber<T>
			implements Subscriber<T>, QueueSubscription<T>, Receiver, Producer,
			           Trackable {

		final Subscriber<? super T> actual;

		final long n;

		long remaining;

		QueueSubscription<T> qs;

		boolean done;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<TakeFuseableSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(TakeFuseableSubscriber.class, "wip");

		int inputMode;

		public TakeFuseableSubscriber(Subscriber<? super T> actual, long n) {
			this.actual = actual;
			this.n = n;
			this.remaining = n;
		}

		@SuppressWarnings("unchecked")
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.qs, s)) {
				this.qs = (QueueSubscription<T>) s;
				actual.onSubscribe(this);

				if (inputMode != Fuseable.SYNC) {
					if (n == 0 && wip == 0) {
						request(Long.MAX_VALUE);
					}
				}
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}

			if (inputMode == Fuseable.ASYNC) {
				actual.onNext(null);
				return;
			}

			long r = remaining;

			if (r == 0) {
				qs.cancel();
				onComplete();
				return;
			}

			remaining = --r;
			boolean stop = r == 0L;

			actual.onNext(t);

			if (stop) {
				qs.cancel();

				onComplete();
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}
			done = true;
			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			actual.onComplete();
		}

		@Override
		public void request(long n) {
			if (wip != 0) {
				qs.request(n);
			}
			else if (WIP.compareAndSet(this, 0, 1)) {
				if (n >= this.n) {
					qs.request(Long.MAX_VALUE);
				}
				else {
					qs.request(n);
				}
			}
		}

		@Override
		public void cancel() {
			qs.cancel();
		}
		@Override
		public boolean isStarted() {
			return qs != null && !done;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public long getCapacity() {
			return n;
		}

		@Override
		public Object upstream() {
			return qs;
		}

		@Override
		public long expectedFromUpstream() {
			return remaining;
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public long limit() {
			return 0;
		}

		@Override
		public int requestFusion(int requestedMode) {
			int m = qs.requestFusion(requestedMode);
			this.inputMode = m;
			return m;
		}

		@Override
		public T poll() {
			if (done) {
				return null;
			}
			long r = remaining;
			if (r == 0L) {
				if (!done) {
					done = true;
					if (inputMode == Fuseable.ASYNC) {
						qs.cancel();
						actual.onComplete();
					}
				}
				return null;
			}

			T v = qs.poll();

			if (v != null) {
				remaining = --r;
				if (r == 0L) {
					if (!done) {
						done = true;
						if (inputMode == Fuseable.ASYNC) {
							qs.cancel();
							actual.onComplete();
						}
					}
				}
			}

			return v;
		}

		@Override
		public boolean isEmpty() {
			return remaining == 0 || qs.isEmpty();
		}

		@Override
		public void clear() {
			qs.clear();
		}

		@Override
		public int size() {
			return qs.size();
		}
	}

}
