package com.viaversion.nbt.internal;

import java.util.Arrays;

/**
 * A tiny primitive int list used to keep the library dependency-free
 * (replaces fastutil's {@code IntArrayList}) while avoiding boxing.
 */
public final class IntArrayList {

    private static final int DEFAULT_CAPACITY = 10;

    private int[] array;
    private int size;

    public IntArrayList() {
        this(DEFAULT_CAPACITY);
    }

    public IntArrayList(final int capacity) {
        this.array = new int[Math.max(1, capacity)];
    }

    public void add(final int value) {
        ensureCapacity(size + 1);
        array[size++] = value;
    }

    public int getInt(final int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return array[index];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int[] toArray() {
        return Arrays.copyOf(array, size);
    }

    public void clear() {
        size = 0;
    }

    private void ensureCapacity(final int minCapacity) {
        if (minCapacity <= array.length) {
            return;
        }
        int newCapacity = array.length + (array.length >> 1) + 1;
        if (newCapacity < minCapacity) {
            newCapacity = minCapacity;
        }
        array = Arrays.copyOf(array, newCapacity);
    }
}
