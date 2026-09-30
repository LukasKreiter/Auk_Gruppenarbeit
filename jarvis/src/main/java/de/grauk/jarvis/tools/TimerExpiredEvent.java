package de.grauk.jarvis.tools;

import java.time.Instant;
import java.util.UUID;

/** Wird veröffentlicht, wenn ein per setTimer gestellter Timer abläuft; web/ leitet es ans Dashboard weiter. */
public record TimerExpiredEvent(UUID timerId, String label, int minutes, Instant expiredAt) {
}
