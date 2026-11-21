package app.wallpapershuffle;

import java.util.*;

/** Pure state machine: persisted shuffle bags, one previous item, independent screen clocks. */
public final class Playback {
    public long current = -1, previous = -1, due = 0, holdUntil = 0;
    public boolean paused = false;
    /** Paused because another wallpaper replaced this screen; cleared when the user sets this app again. */
    public boolean replaced = false;
    public final ArrayList<Long> bag = new ArrayList<>();
    public final HashSet<Long> cycle = new HashSet<>();

    public boolean held(long now) { return paused || holdUntil > now; }
    public static boolean eventMatches(String mode, String event) {
        return mode.equals(event) || (mode.equals("both") && (event.equals("unlock") || event.equals("timer")));
    }
    public boolean shouldAdvance(String mode, String event, long now) {
        return !held(now) && eventMatches(mode, event) && (!event.equals("timer") || (due > 0 && now >= due));
    }
    public long next(List<Long> ids, boolean random, Random rng, long now, long interval) {
        if (ids.isEmpty()) { current = -1; previous = -1; bag.clear(); cycle.clear(); due = 0; return -1; }
        long chosen;
        if (random) {
            HashSet<Long> valid = new HashSet<>(ids);
            bag.removeIf(id -> !valid.contains(id));
            cycle.retainAll(valid);
            ArrayList<Long> fresh = new ArrayList<>();
            for (long id : ids) if (!cycle.contains(id) && !bag.contains(id)) fresh.add(id);
            Collections.shuffle(fresh, rng);
            // Newly imported items join the unfinished cycle exactly once.
            bag.addAll(fresh);
            if (bag.isEmpty()) { cycle.clear(); bag.addAll(ids); Collections.shuffle(bag, rng); }
            if (bag.size() > 1 && bag.get(0) == current) Collections.swap(bag, 0, 1);
            chosen = bag.remove(0); cycle.add(chosen);
        } else {
            int i = ids.indexOf(current);
            chosen = ids.get((i + 1) % ids.size());
        }
        if (current != chosen) previous = current;
        current = chosen;
        due = now + interval;
        return chosen;
    }
    public void previous(long now, long interval, List<Long> valid) {
        if (valid.contains(previous)) { long old = current; current = previous; previous = old; due = now + interval; }
    }
    public void choose(long id, long now, long interval) {
        if (id != current) previous = current;
        current = id; bag.remove(id); cycle.add(id); due = now + interval;
    }
}
