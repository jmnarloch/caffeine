/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.Async.AsyncWeigher;
import com.github.benmanes.caffeine.cache.References.WeakKeyReference;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.testing.DescriptionBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * A matcher that evaluates a {@link BoundedLocalCache} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidBoundedLocalCache<K, V>
    extends TypeSafeDiagnosingMatcher<BoundedLocalCache<K, V>> {
  DescriptionBuilder desc;

  @Override
  public void describeTo(Description description) {
    description.appendText("valid bounded cache");
    if (desc.getDescription() != description) {
      description.appendText(desc.getDescription().toString());
    }
  }

  @Override
  protected boolean matchesSafely(BoundedLocalCache<K, V> cache, Description description) {
    desc = new DescriptionBuilder(description);

    drain(cache);
    checkReadBuffer(cache);
    checkCache(cache, desc);
    checkEvictionDeque(cache, desc);

    if (!desc.matches()) {
      throw new AssertionError(desc.getDescription().toString());
    }
    return true;
  }

  private void drain(BoundedLocalCache<K, V> cache) {
    do {
      cache.cleanUp();
    } while (cache.buffersWrites() && !cache.writeQueue().isEmpty());
  }

  private void checkReadBuffer(BoundedLocalCache<K, V> cache) {
    if (!cache.evicts() && !cache.expiresAfterAccess()) {
      return;
    }
    Buffer<?> buffer = cache.readBuffer;
    desc.expectThat("buffer is empty", buffer.size(), is(0));
    desc.expectThat("buffer reads = writes", buffer.reads(), is(buffer.writes()));
  }

  private void checkCache(BoundedLocalCache<K, V> cache, DescriptionBuilder desc) {
    desc.expectThat("Inconsistent size", cache.data.size(), is(cache.size()));
    if (cache.evicts()) {
      desc.expectThat("overflow", cache.maximum(),
          is(greaterThanOrEqualTo(cache.adjustedWeightedSize())));
    }

    boolean locked = (cache.evictionLock instanceof NonReentrantLock)
        ? ((NonReentrantLock) cache.evictionLock).isLocked()
        : ((ReentrantLock) cache.evictionLock).isLocked();
    desc.expectThat("locked", locked, is(false));

    if (cache.isEmpty()) {
      desc.expectThat("empty map", cache, emptyMap());
    }

    for (Node<K, V> node : cache.data.values()) {
      checkNode(cache, node, desc);
    }
  }

  private void checkEvictionDeque(BoundedLocalCache<K, V> cache, DescriptionBuilder desc) {
    if (cache.evicts()) {
      List<LinkedDeque<Node<K, V>>> deques = new ArrayList<>(Arrays.asList(
          cache.accessOrderEdenDeque(),
          cache.accessOrderProbationDeque(),
          cache.accessOrderProtectedDeque()));
      if (cache.isWeighted()) {
        deques.add(cache.accessOrderZeroWeightDeque());
        checkDeque(cache.accessOrderZeroWeightDeque(), desc);
      }

      checkLinks(cache, deques, desc);
      checkDeque(cache.accessOrderEdenDeque(), desc);
      checkDeque(cache.accessOrderProbationDeque(), desc);
    } else if (cache.expiresAfterAccess()) {
      checkLinks(cache, ImmutableList.of(cache.accessOrderEdenDeque()), desc);
      checkDeque(cache.accessOrderEdenDeque(), desc);
    }

    if (cache.expiresAfterWrite()) {
      checkLinks(cache, ImmutableList.of(cache.writeOrderDeque()), desc);
      checkDeque(cache.writeOrderDeque(), desc);
    }
  }

  private void checkDeque(LinkedDeque<Node<K, V>> deque, DescriptionBuilder desc) {
    IsValidLinkedDeque.<Node<K, V>>validLinkedDeque().matchesSafely(deque, desc.getDescription());
  }

  private void checkLinks(BoundedLocalCache<K, V> cache,
      List<LinkedDeque<Node<K, V>>> deques, DescriptionBuilder desc) {
    int size = 0;
    long weightedSize = 0;
    Set<Node<K, V>> seen = Sets.newIdentityHashSet();
    for (LinkedDeque<Node<K, V>> deque : deques) {
      size += deque.size();
      weightedSize += scanLinks(cache, seen, deque, desc);
    }
    if (cache.size() != size) {
      desc.expectThat(() -> "deque size " + deques, size, is(cache.size()));
    }

    Supplier<String> errorMsg = () -> String.format(
        "Size != list length; pending=%s, additional: %s", cache.writeQueue().size(),
        Sets.difference(seen, ImmutableSet.copyOf(cache.data.values())));
    desc.expectThat(errorMsg, cache.size(), is(seen.size()));

    final long weighted = weightedSize;
    if (cache.evicts()) {
      Supplier<String> error = () -> String.format(
          "WeightedSize != link weights [%d vs %d] {%d vs %d}",
          cache.adjustedWeightedSize(), weighted, seen.size(), cache.size());
      desc.expectThat("non-negative weight", weightedSize, is(greaterThanOrEqualTo(0L)));
      desc.expectThat(error, cache.adjustedWeightedSize(), is(weightedSize));
    }
  }

  private long scanLinks(BoundedLocalCache<K, V> cache, Set<Node<K, V>> seen,
      LinkedDeque<Node<K, V>> deque, DescriptionBuilder desc) {
    long weightedSize = 0;
    Node<?, ?> prev = null;
    for (Node<K, V> node : deque) {
      Supplier<String> errorMsg = () -> String.format(
          "Loop detected: %s, saw %s in %s", node, seen, cache);
      desc.expectThat("wrong previous", deque.getPrevious(node), is(prev));
      desc.expectThat(errorMsg, seen.add(node), is(true));
      weightedSize += node.getWeight();
      prev = node;
    }
    return weightedSize;
  }

  private void checkNode(BoundedLocalCache<K, V> cache, Node<K, V> node, DescriptionBuilder desc) {
    Weigher<? super K, ? super V> weigher = cache.weigher;
    V value = node.getValue();
    K key = node.getKey();

    desc.expectThat("weight", node.getWeight(), is(greaterThanOrEqualTo(0)));

    boolean canCheckWeight = weigher == CacheWeigher.RANDOM;
    if (weigher instanceof AsyncWeigher) {
      canCheckWeight = ((AsyncWeigher<?, ?>) weigher).delegate == CacheWeigher.RANDOM;
    }
    if (canCheckWeight) {
      desc.expectThat("weight", node.getWeight(), is(weigher.weigh(key, value)));
    }

    if (cache.collectKeys()) {
      if ((key != null) && (value != null)) {
        desc.expectThat("inconsistent", cache.containsKey(key), is(true));
      }
      desc.expectThat("Invalid reference type",
          node.getKeyReference(), instanceOf(WeakKeyReference.class));
    } else {
      desc.expectThat("not null key", key, is(not(nullValue())));
    }
    desc.expectThat("found wrong node", cache.data.get(node.getKeyReference()), is(node));

    if (!cache.collectValues()) {
      desc.expectThat("not null value", value, is(not(nullValue())));
      if ((key != null) && !cache.hasExpired(node, cache.expirationTicker().read())) {
        desc.expectThat(() -> "Could not find key: " + key + ", value: " + value,
            cache.containsValue(value), is(true));
      }
    }

    if (value instanceof CompletableFuture<?>) {
      CompletableFuture<?> future = (CompletableFuture<?>) value;
      boolean success = future.isDone() && !future.isCompletedExceptionally();
      desc.expectThat("future is done", success, is(true));
      desc.expectThat("not null value", future.getNow(null), is(not(nullValue())));
    }
  }

  public static <K, V> IsValidBoundedLocalCache<K, V> valid() {
    return new IsValidBoundedLocalCache<K, V>();
  }
}
