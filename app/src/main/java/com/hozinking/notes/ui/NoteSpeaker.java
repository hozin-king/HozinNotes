package com.hozinking.notes.ui;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * v1.17: bacakan catatan memakai TextToSpeech bawaan sistem.
 * Tanpa izin tambahan, tanpa perubahan database.
 */
public class NoteSpeaker {

    private static final int MAX_CHARS = 4000;

    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile boolean speaking;
    private String pending;

    public NoteSpeaker(Context ctx) {
        Context app = ctx.getApplicationContext();
        tts = new TextToSpeech(app, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            // Utamakan Bahasa Indonesia bila tersedia di perangkat.
            Locale id = new Locale("id", "ID");
            int r = tts.setLanguage(id);
            if (r == TextToSpeech.LANG_MISSING_DATA
                    || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    speaking = true;
                }

                @Override
                public void onDone(String utteranceId) {
                    speaking = false;
                }

                @Override
                public void onError(String utteranceId) {
                    speaking = false;
                }
            });
            ready = true;
            if (pending != null) {
                String p = pending;
                pending = null;
                speakNow(p);
            }
        });
    }

    public boolean isSpeaking() {
        return speaking;
    }

    /** Toggle: sedang bicara -> berhenti; diam -> bacakan teks. */
    public void toggle(String text) {
        if (speaking) {
            stop();
            return;
        }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        if (t.length() > MAX_CHARS) t = t.substring(0, MAX_CHARS);
        if (!ready) {
            pending = t;
            return;
        }
        speakNow(t);
    }

    private void speakNow(String text) {
        if (tts == null) return;
        speaking = true;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hozin-note");
    }

    public void stop() {
        speaking = false;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    public void shutdown() {
        stop();
        ready = false;
        pending = null;
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
    }
}
