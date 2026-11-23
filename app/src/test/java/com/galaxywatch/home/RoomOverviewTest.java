package com.galaxywatch.home;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RoomOverviewTest {
    private static final Rooms.Room LIVING = Rooms.find("living");
    private static Map<Rooms.Light, Boolean> off() {
        Map<Rooms.Light, Boolean> result = new HashMap<>();
        for (var light : Rooms.MASTER_LIGHTS) result.put(light, false);
        return result;
    }

    @Test public void zeroRequiresEveryLightAndPartialCountsAreLowerBounds() {
        var states = off();
        assertEquals("", RoomOverview.status(LIVING, states).mark());
        states.remove(LIVING.lights().get(0));
        assertEquals("?", RoomOverview.status(LIVING, states).mark());
        states.put(LIVING.lights().get(1), true);
        assertEquals("1+", RoomOverview.status(LIVING, states).mark());
        states.put(LIVING.lights().get(0), true);
        assertEquals("2", RoomOverview.status(LIVING, states).mark());
    }

    @Test public void anExistingFavoriteSwapsAndAnAbsentOneReplacesOnlyItsSlot() {
        List<Rooms.Room> defaults = RoomOverview.favorites("");
        var swapped = RoomOverview.replaceSlot(defaults, 0, Rooms.find("hall"));
        assertEquals(List.of(Rooms.find("hall"), Rooms.find("bedroom"), LIVING, Rooms.find("kids")), swapped);
        var replaced = RoomOverview.replaceSlot(swapped, 3, Rooms.find("shower"));
        assertEquals(Rooms.find("shower"), replaced.get(3));
        assertEquals(swapped.subList(0, 3), replaced.subList(0, 3));
        assertEquals(LIVING, defaults.get(0));
        assertEquals(List.of(Rooms.find("wc"), LIVING, Rooms.find("bedroom"), Rooms.find("hall")),
            RoomOverview.favorites("bad,wc,wc"));
    }

    @Test public void outsideCountsRoomsNotCircuitsAndNeverIncludesGroupOnlyOutputs() {
        var favorites = RoomOverview.favorites("");
        var states = off();
        states.put(Rooms.MASTER_LIGHTS.get(Rooms.MASTER_LIGHTS.size() - 1), true);
        for (var light : LIVING.lights()) states.put(light, true);
        assertEquals("", RoomOverview.outsideHint(favorites, states));
        states.put(Rooms.find("shower").lights().get(0), true);
        assertEquals("Ещё горит в душевой", RoomOverview.outsideHint(favorites, states));
        states.put(Rooms.find("office").lights().get(0), true);
        assertEquals("Ещё в 2 комнатах", RoomOverview.outsideHint(favorites, states));
        states.put(Rooms.find("wc").lights().get(0), true);
        assertEquals("Ещё в 3 комнатах", RoomOverview.outsideHint(favorites, states));
        var withHiddenLiving = RoomOverview.replaceSlot(favorites, 0, Rooms.find("shower"));
        assertEquals("Ещё в 3 комнатах", RoomOverview.outsideHint(withHiddenLiving, states));
    }

    @Test public void missingOutsideDataNeverMeansTheHomeIsDark() {
        var values = off();
        values.remove(Rooms.find("wc").lights().get(0));
        assertEquals("Не всё известно", RoomOverview.outsideHint(RoomOverview.favorites(""), values));
    }

    @Test public void eachRealLightExpiresIndependentlyIncludingWhenTheClockMovesBackwards() {
        var a = LIVING.lights().get(0);
        var b = LIVING.lights().get(1);
        long first = 1_000_000L, second = first + 4000, expiry = first + RoomCache.FRESH_MS;
        var snapshot = new RoomCache.Snapshot(Map.of(a, new RoomCache.Reading(true, first),
            b, new RoomCache.Reading(false, second)), false);
        assertTrue(snapshot.statesAt(first - 1).isEmpty());
        assertEquals(List.of(expiry, second + RoomCache.FRESH_MS), snapshot.expiriesAfter(second));
        assertEquals(Map.of(b, false), snapshot.statesAt(expiry));
        assertTrue(snapshot.statesAt(second + RoomCache.FRESH_MS).isEmpty());
        var demo = new RoomCache.Snapshot(snapshot.readings(), true);
        assertEquals(Map.of(a, true, b, false), demo.statesAt(second + RoomCache.FRESH_MS));
        assertTrue(demo.expiriesAfter(second).isEmpty());
    }
}
