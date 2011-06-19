/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.remoting3;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static java.lang.System.identityHashCode;

final class UnlockedReadIdentityHashMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {

    private static final int DEFAULT_INITIAL_CAPACITY = 512;
    private static final int MAXIMUM_CAPACITY = 1 << 30;
    private static final float DEFAULT_LOAD_FACTOR = 0.60f;

    // Final fields (thread-safe)
    private final Object writeLock = new Object();
    private final Set<Entry<K, V>> entrySet = new EntrySet();
    private final float loadFactor;

    // Volatile fields (writes protected by {@link #writeLock})
    private volatile int size;
    private volatile AtomicReferenceArray<Item<K,V>[]> table;

    // Raw fields (reads and writes protected by {@link #writeLock}
    private int threshold;

    UnlockedReadIdentityHashMap(int initialCapacity, final float loadFactor) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity must be > 0");
        }
        if (initialCapacity > MAXIMUM_CAPACITY) {
            initialCapacity = MAXIMUM_CAPACITY;
        }
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
            throw new IllegalArgumentException("Load factor must be > 0.0f");
        }

        int capacity = 1;

        while (capacity < initialCapacity) {
            capacity <<= 1;
        }

        this.loadFactor = loadFactor;
        synchronized (writeLock) {
            threshold = (int)(capacity * loadFactor);
            table = new AtomicReferenceArray<Item<K, V>[]>(capacity);
        }
    }

    UnlockedReadIdentityHashMap(final float loadFactor) {
        this(DEFAULT_INITIAL_CAPACITY, loadFactor);
    }

    UnlockedReadIdentityHashMap(final int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    UnlockedReadIdentityHashMap() {
        this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR);
    }

    @SuppressWarnings( { "unchecked" })
    private void resize() {
        assert Thread.holdsLock(writeLock);
        final AtomicReferenceArray<Item<K, V>[]> oldTable = table;
        final int oldCapacity = oldTable.length();
        if (oldCapacity == MAXIMUM_CAPACITY) {
            return;
        }
        final int newCapacity = oldCapacity << 1;
        final AtomicReferenceArray<Item<K, V>[]> newTable = new AtomicReferenceArray<Item<K, V>[]>(newCapacity);
        final int newThreshold = (int)(newCapacity * loadFactor);
        for (int i = 0; i < oldCapacity; i ++) {
            final Item<K, V>[] items = oldTable.get(i);
            if (items != null) {
                final int length = items.length;
                for (int j = 0; j < length; j++) {
                    Item<K, V> item = items[j];
                    final int hc = identityHashCode(item) & (newCapacity - 1);
                    final Item<K, V>[] old = newTable.get(hc);
                    if (old == null) {
                        newTable.lazySet(hc, new Item[] { item });
                    } else {
                        final int oldLen = old.length;
                        final Item<K, V>[] copy = Arrays.copyOf(old, oldLen + 1);
                        copy[oldLen] = item;
                        newTable.lazySet(hc, copy);
                    }
                }
            }
        }
        table = newTable;
        threshold = newThreshold;
    }

    private static <K, V> Item<K, V> doGet(final AtomicReferenceArray<Item<K, V>[]> table, final Object key) {
        Item<K, V>[] row = doGetRow(table, key);
        return row == null ? null : doGet(row, key);
    }

    private static <K, V> Item<K, V>[] doGetRow(final AtomicReferenceArray<Item<K, V>[]> table, final Object key) {
        final int hc = getIndex(table, key);
        return doGetRow(table, hc);
    }

    private static <K, V> int getIndex(final AtomicReferenceArray<Item<K, V>[]> table, final Object key) {
        return identityHashCode(key) & (table.length() - 1);
    }

    private static <K, V> Item<K, V>[] doGetRow(final AtomicReferenceArray<Item<K, V>[]> table, final int hc) {
        return table.get(hc);
    }

    private static <K, V> Item<K, V> doGet(Item<K, V>[] row, Object key) {
        for (Item<K, V> item : row) {
            if (key == item.key) {
                return item;
            }
        }
        return null;
    }

    private V doPut(AtomicReferenceArray<Item<K, V>[]> table, K key, V value, boolean ifAbsent) {
        final int hc = getIndex(table, key);
        final Item<K, V>[] old = doGetRow(table, hc);
        if (old == null) {
            @SuppressWarnings( { "unchecked" })
            final Item<K, V>[] newRow = new Item[] { new Item<K, V>(key, value) };
            table.set(hc, newRow);
            if (size++ == threshold) {
                resize();
            }
            return null;
        } else {
            final Item<K, V> item = doGet(old, key);
            if (item != null) {
                try {
                    return item.value;
                } finally {
                    if (! ifAbsent) item.value = value;
                }
            }
            final int oldLen = old.length;
            final Item<K, V>[] newRow = Arrays.copyOf(old, oldLen + 1);
            newRow[oldLen] = new Item<K, V>(key, value);
            table.set(hc, newRow);
            if (size++ == threshold) {
                resize();
            }
            return null;
        }
    }

    private static <K, V> Item<K, V>[] remove(Item<K, V>[] row, int idx) {
        final int len = row.length;
        assert idx < len;
        if (len == 1) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Item<K, V>[] newRow = new Item[len - 1];
        if (idx > 0) {
            System.arraycopy(row, 0, newRow, 0, idx);
        }
        if (idx < len - 1) {
            System.arraycopy(row, idx + 1, newRow, idx, len - 1 - idx);
        }
        return newRow;
    }

    public Set<Entry<K, V>> entrySet() {
        return entrySet;
    }

    public int size() {
        return size;
    }

    public boolean containsKey(final Object key) {
        if (key == null) {
            return false;
        }
        final Item<K, V> item = doGet(table, key);
        return item != null;
    }

    public V get(final Object key) {
        if (key == null) {
            return null;
        }
        final Item<K, V> item = doGet(table, key);
        return item == null ? null : item.value;
    }

    public V put(final K key, final V value) {
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        synchronized (writeLock) {
            return doPut(table, key, value, false);
        }
    }

    public V remove(final Object key) {
        if (key == null) {
            return null;
        }
        synchronized (writeLock) {
            final int hc = getIndex(table, key);
            final Item<K, V>[] row = doGetRow(table, hc);
            if (row == null) {
                return null;
            }
            final int rowLen = row.length;
            for (int i = 0; i < rowLen; i++) {
                final Item<K, V> item = row[i];
                if (key == item.key) {
                    table.set(hc, remove(row, i));
                    size --;
                    return item.value;
                }
            }
            return null;
        }
    }

    public void clear() {
        synchronized (writeLock) {
            table = new AtomicReferenceArray<Item<K, V>[]>(table.length());
            size = 0;
        }
    }

    public V putIfAbsent(final K key, final V value) {
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        synchronized (writeLock) {
            return doPut(table, key, value, true);
        }
    }

    public boolean remove(final Object key, final Object value) {
        if (key == null) {
            return false;
        }
        synchronized (writeLock) {
            final int hc = getIndex(table, key);
            final Item<K, V>[] row = doGetRow(table, hc);
            if (row == null) {
                return false;
            }
            final int rowLen = row.length;
            for (int i = 0; i < rowLen; i++) {
                final Item<K, V> item = row[i];
                if (key == item.key && (value == null ? item.value == null : value.equals(item.value))) {
                    table.set(hc, remove(row, i));
                    size --;
                    return true;
                }
            }
            return false;
        }
    }

    public boolean replace(final K key, final V oldValue, final V newValue) {
        if (key == null) {
            return false;
        }
        synchronized (writeLock) {
            final Item<K, V> item = doGet(table, key);
            if (item != null) {
                if (oldValue == null ? item.value == null : oldValue.equals(item.value)) {
                    item.value = newValue;
                    return true;
                }
            }
            return false;
        }
    }

    public V replace(final K key, final V value) {
        if (key == null) {
            return null;
        }
        synchronized (writeLock) {
            final Item<K, V> item = doGet(table, key);
            if (item != null) try {
                return item.value;
            } finally {
                item.value = value;
            }
            return null;
        }
    }

    private final class EntrySet extends AbstractSet<Entry<K, V>> implements Set<Entry<K, V>> {

        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        public int size() {
            return UnlockedReadIdentityHashMap.this.size();
        }
    }

    private final class EntryIterator implements Iterator<Entry<K, V>> {
        private final AtomicReferenceArray<Item<K,V>[]> table = UnlockedReadIdentityHashMap.this.table;
        private int tableIdx;
        private int itemIdx;
        private Item<K, V> next;

        public boolean hasNext() {
            while (next == null) {
                if (table.length() == tableIdx) {
                    return false;
                }
                final Item<K, V>[] items = table.get(tableIdx);
                if (items != null) {
                    final int len = items.length;
                    if (itemIdx < len) {
                        next = items[itemIdx++];
                        return true;
                    }
                }
                itemIdx = 0;
                tableIdx++;
            }
            return true;
        }

        public Entry<K, V> next() {
            if (hasNext()) try {
                return next;
            } finally {
                next = null;
            }
            throw new NoSuchElementException();
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Item<K, V> implements Entry<K, V> {
        private final K key;
        private volatile V value;

        private Item(final K key, final V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        public V setValue(final V value) {
            try {
                return this.value;
            } finally {
                this.value = value;
            }
        }

        public int hashCode() {
            return identityHashCode(key);
        }

        public boolean equals(final Object obj) {
            return obj instanceof Item && equals((Item<?,?>) obj);
        }

        public boolean equals(final Item<?, ?> obj) {
            return obj != null && obj.key == key;
        }
    }
}
