package com.hozinking.notes.ui;

import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

/**
 * Helper Java untuk transformasi PagingData.
 *
 * Di Paging 3, map/filter/insertSeparators adalah extension function Kotlin
 * (di {@link PagingDataTransforms}), bukan method instance PagingData —
 * jadi dari Java harus dipanggil sebagai static dengan Executor eksplisit.
 * Kelas ini membungkusnya agar call site tetap ringkas.
 */
final class PagingXforms {

    /** Executor latar untuk transformasi paging (jalan di luar UI thread). */
    private static final Executor EXEC = Executors.newSingleThreadExecutor();

    private PagingXforms() {
    }

    /** Petakan tiap item PagingData ke tipe lain. */
    static <T, R> PagingData<R> map(PagingData<T> data,
                                    Function1<? super T, ? extends R> transform) {
        return PagingDataTransforms.map(data, EXEC, transform);
    }

    /** Sisipkan separator (mis. header seksi) di antara item. */
    static <T> PagingData<T> insertSeparators(
            PagingData<T> data,
            Function2<? super T, ? super T, ? extends T> generator) {
        return PagingDataTransforms.insertSeparators(data, EXEC, generator);
    }
}
