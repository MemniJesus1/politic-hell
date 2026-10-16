package com.politichell.hud;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Есть ли у игрока голосовой чат (мод Plasmo Voice на клиенте и подключение к серверу).
 * Источники: ручной (/hudvoice, для тестов) и Plasmo Voice (автоматически, через %plasmovoice_installed% — см. Integrations).
 */
public interface VoiceProvider {
    boolean hasVoice(UUID player);

    /** Ручная заглушка для тестов. */
    class Manual implements VoiceProvider {
        public final Set<UUID> on = ConcurrentHashMap.newKeySet();
        @Override public boolean hasVoice(UUID p) { return on.contains(p) || Integrations.VOICE.contains(p); }   // вручную ИЛИ реальный Plasmo Voice
    }
}
