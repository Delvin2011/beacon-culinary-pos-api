package com.beaconculinary.api.endofday;

import com.beaconculinary.api.common.UtcTimestamps;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Reports R3: the append-only shift_signoffs log. A shift's sign-off state is its latest row. */
@Component
@AllArgsConstructor
public class ShiftSignoffs {
    private final NamedParameterJdbcTemplate jdbc;

    /** The latest row of a shift: SIGNED_OFF, or REVOKED (which reads as not signed off). */
    public record State(String action, String byName, Instant at, String note) {
        public boolean signedOff() {
            return "SIGNED_OFF".equals(action);
        }
    }

    /** Current state of each shift that has any sign-off row; shifts without one are absent. */
    public Map<Long, State> latest(Collection<Long> shiftIds) {
        var states = new HashMap<Long, State>();
        if (shiftIds.isEmpty()) {
            return states;
        }
        jdbc.query("""
                SELECT s.shift_id, s.action, u.name AS by_name, s.done_at, s.note
                FROM shift_signoffs s
                JOIN users u ON u.id = s.done_by
                WHERE s.shift_id IN (:ids)
                  AND s.id = (SELECT MAX(x.id) FROM shift_signoffs x WHERE x.shift_id = s.shift_id)
                """, new MapSqlParameterSource("ids", shiftIds), rs -> {
            states.put(rs.getLong("shift_id"), new State(rs.getString("action"), rs.getString("by_name"),
                    UtcTimestamps.toInstant(rs.getObject("done_at", LocalDateTime.class)), rs.getString("note")));
        });
        return states;
    }

    public boolean isSignedOff(long shiftId) {
        var state = latest(java.util.List.of(shiftId)).get(shiftId);
        return state != null && state.signedOff();
    }

    void record(long shiftId, String action, long doneBy, String note) {
        jdbc.update("INSERT INTO shift_signoffs (shift_id, action, done_by, note) VALUES (:shiftId, :action, :doneBy, :note)",
                new MapSqlParameterSource().addValue("shiftId", shiftId).addValue("action", action)
                        .addValue("doneBy", doneBy).addValue("note", note));
    }
}
