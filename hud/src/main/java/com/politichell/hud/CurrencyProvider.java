package com.politichell.hud;

import java.util.UUID;

/**
 * Источник балансов. Сейчас используется {@link FileCurrencyProvider} (заглушка).
 * Когда появится настоящая экономика (Vault / CoinsEngine / PlayerPoints) —
 * реализуй этот интерфейс и вызови {@code PoliticHellHud.setProvider(...)}.
 * get() вызывается из главного потока несколько раз в секунду — не делай там тяжёлых запросов (кешируй).
 */
public interface CurrencyProvider {
    long get(UUID player, Currency currency);

    void set(UUID player, Currency currency, long amount);

    default void add(UUID player, Currency currency, long delta) {
        set(player, currency, get(player, currency) + delta);
    }

    /** Вызывается при выключении плагина — сохранить данные. */
    default void save() {}
}
