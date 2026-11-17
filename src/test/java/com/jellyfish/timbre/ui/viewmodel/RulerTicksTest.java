/*
 * Copyright 2026 Jellyfish Ng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jellyfish.timbre.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Ruler tick tests. The expected lists (x | frame | major or minor | label) were produced by an independent
 * exact-fraction script (build-logs/m3a/gen_ticks.py) and checked by hand for the first entries.
 */
class RulerTicksTest {

    // A micro: rate=44100 fpp=1/20 scroll=0 width=200 major=1/10000s
    private static final String[] CASE_0 = {"0.0|0|M|0.0000",
    "8.82|0|m|",
    "17.64|1|m|",
    "26.46|1|m|",
    "35.28|2|m|",
    "44.1|2|m|",
    "52.92|3|m|",
    "61.74|3|m|",
    "70.56|4|m|",
    "79.38|4|m|",
    "88.2|4|M|0.0001",
    "97.02|5|m|",
    "105.84|5|m|",
    "114.66|6|m|",
    "123.48|6|m|",
    "132.3|7|m|",
    "141.12|7|m|",
    "149.94|7|m|",
    "158.76|8|m|",
    "167.58|8|m|",
    "176.4|9|M|0.0002",
    "185.22|9|m|",
    "194.04|10|m|"};

    @Test
    void knownAnswersAmicro() {
        assertTicks(CASE_0, RulerTicks.generate(new TimeAxis(44100, 1.0 / 20, 0L), 200));
    }

    // B milli: rate=44100 fpp=441/10 scroll=441000 width=250 major=1/10s
    private static final String[] CASE_1 = {"0.0|441000|M|10.0",
    "10.0|441441|m|",
    "20.0|441882|m|",
    "30.0|442323|m|",
    "40.0|442764|m|",
    "50.0|443205|m|",
    "60.0|443646|m|",
    "70.0|444087|m|",
    "80.0|444528|m|",
    "90.0|444969|m|",
    "100.0|445410|M|10.1",
    "110.0|445851|m|",
    "120.0|446292|m|",
    "130.0|446733|m|",
    "140.0|447174|m|",
    "150.0|447615|m|",
    "160.0|448056|m|",
    "170.0|448497|m|",
    "180.0|448938|m|",
    "190.0|449379|m|",
    "200.0|449820|M|10.2",
    "210.0|450261|m|",
    "220.0|450702|m|",
    "230.0|451143|m|",
    "240.0|451584|m|",
    "250.0|452025|m|"};

    @Test
    void knownAnswersBmilli() {
        assertTicks(CASE_1, RulerTicks.generate(new TimeAxis(44100, 44.1, 441000L), 250));
    }

    // C minutes: rate=44100 fpp=22050 scroll=2425500 width=300 major=50s
    private static final String[] CASE_2 = {"10.0|2646000|m|",
    "30.0|3087000|m|",
    "50.0|3528000|m|",
    "70.0|3969000|m|",
    "90.0|4410000|M|1:40",
    "110.0|4851000|m|",
    "130.0|5292000|m|",
    "150.0|5733000|m|",
    "170.0|6174000|m|",
    "190.0|6615000|M|2:30",
    "210.0|7056000|m|",
    "230.0|7497000|m|",
    "250.0|7938000|m|",
    "270.0|8379000|m|",
    "290.0|8820000|M|3:20"};

    @Test
    void knownAnswersCminutes() {
        assertTicks(CASE_2, RulerTicks.generate(new TimeAxis(44100, 22050, 2425500L), 300));
    }

    // D hours: rate=48000 fpp=1440000 scroll=172800000 width=300 major=3600s
    private static final String[] CASE_3 = {"0.0|172800000|M|1:00:00",
    "20.0|201600000|m|",
    "40.0|230400000|m|",
    "60.0|259200000|m|",
    "80.0|288000000|m|",
    "100.0|316800000|m|",
    "120.0|345600000|M|2:00:00",
    "140.0|374400000|m|",
    "160.0|403200000|m|",
    "180.0|432000000|m|",
    "200.0|460800000|m|",
    "220.0|489600000|m|",
    "240.0|518400000|M|3:00:00",
    "260.0|547200000|m|",
    "280.0|576000000|m|",
    "300.0|604800000|m|"};

    @Test
    void knownAnswersDhours() {
        assertTicks(CASE_3, RulerTicks.generate(new TimeAxis(48000, 1440000, 172800000L), 300));
    }

    // E five: rate=44100 fpp=2205 scroll=0 width=300 major=5s
    private static final String[] CASE_4 = {"0.0|0|M|0",
    "20.0|44100|m|",
    "40.0|88200|m|",
    "60.0|132300|m|",
    "80.0|176400|m|",
    "100.0|220500|M|5",
    "120.0|264600|m|",
    "140.0|308700|m|",
    "160.0|352800|m|",
    "180.0|396900|m|",
    "200.0|441000|M|10",
    "220.0|485100|m|",
    "240.0|529200|m|",
    "260.0|573300|m|",
    "280.0|617400|m|",
    "300.0|661500|M|15"};

    @Test
    void knownAnswersEfive() {
        assertTicks(CASE_4, RulerTicks.generate(new TimeAxis(44100, 2205, 0L), 300));
    }

    // F two: rate=44100 fpp=882 scroll=0 width=250 major=2s
    private static final String[] CASE_5 = {"0.0|0|M|0",
    "50.0|44100|m|",
    "100.0|88200|M|2",
    "150.0|132300|m|",
    "200.0|176400|M|4",
    "250.0|220500|m|"};

    @Test
    void knownAnswersFtwo() {
        assertTicks(CASE_5, RulerTicks.generate(new TimeAxis(44100, 882, 0L), 250));
    }

    // G offset seconds: rate=44100 fpp=441 scroll=22050 width=250 major=1s
    private static final String[] CASE_6 = {"0.0|22050|m|",
    "10.0|26460|m|",
    "20.0|30870|m|",
    "30.0|35280|m|",
    "40.0|39690|m|",
    "50.0|44100|M|1",
    "60.0|48510|m|",
    "70.0|52920|m|",
    "80.0|57330|m|",
    "90.0|61740|m|",
    "100.0|66150|m|",
    "110.0|70560|m|",
    "120.0|74970|m|",
    "130.0|79380|m|",
    "140.0|83790|m|",
    "150.0|88200|M|2",
    "160.0|92610|m|",
    "170.0|97020|m|",
    "180.0|101430|m|",
    "190.0|105840|m|",
    "200.0|110250|m|",
    "210.0|114660|m|",
    "220.0|119070|m|",
    "230.0|123480|m|",
    "240.0|127890|m|",
    "250.0|132300|M|3"};

    @Test
    void knownAnswersGoffsetseconds() {
        assertTicks(CASE_6, RulerTicks.generate(new TimeAxis(44100, 441, 22050L), 250));
    }

    private static void assertTicks(String[] expected, List<RulerTicks.Tick> actual) {
        assertEquals(expected.length, actual.size(), "tick count");
        for (int i = 0; i < expected.length; i++) {
            String[] p = expected[i].split("\\|", -1);
            RulerTicks.Tick t = actual.get(i);
            String w = "tick " + i + " " + expected[i] + " vs " + t;
            assertEquals(Double.parseDouble(p[0]), t.x(), 1e-6, w);
            assertEquals(Long.parseLong(p[1]), t.frame(), w);
            assertEquals(p[2].equals("M"), t.major(), w);
            assertEquals(p[3], t.label(), w);
        }
    }

    private static final double[] SEQUENCE = {1e-6, 2e-6, 5e-6, 1e-5, 2e-5, 5e-5, 1e-4, 2e-4, 5e-4, 1e-3, 2e-3,
        5e-3, 1e-2, 2e-2, 5e-2, 0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 3600};

    @Test
    void spacingRulesAndExactMultiplesOverManyAxes() {
        Random r = new Random(2024);
        int checkedMajors = 0;
        for (int iter = 0; iter < 3000; iter++) {
            int rate = new int[] {8000, 44100, 48000, 96000}[r.nextInt(4)];
            double fpp = Math.pow(10, -1.5 + r.nextDouble() * 6.5); // about 0.03 to 100000
            long scroll = (long) (r.nextDouble() * 1e9);
            int width = 100 + r.nextInt(1900);
            List<RulerTicks.Tick> ticks = RulerTicks.generate(new TimeAxis(rate, fpp, scroll), width);
            double prevMajorX = Double.NaN;
            double prevX = Double.NaN;
            double stepSeconds = Double.NaN;
            double minorSeconds = Double.NaN;
            for (RulerTicks.Tick t : ticks) {
                assertTrue(t.x() >= 0 && t.x() <= width, "inside the view");
                assertEquals(t.major(), !t.label().isEmpty(), "labels only on major ticks");
                if (!Double.isNaN(prevX)) {
                    assertTrue(t.x() > prevX, "strictly ascending");
                    assertTrue(t.x() - prevX >= 6.0 - 1e-6,
                            "ticks closer than 6 px: " + (t.x() - prevX) + " at fpp " + fpp);
                }
                if (t.major()) {
                    if (!Double.isNaN(prevMajorX)) {
                        assertTrue(t.x() - prevMajorX >= 80.0 - 1e-6,
                                "majors closer than 80 px: " + (t.x() - prevMajorX) + " at fpp " + fpp);
                        double step = (t.x() - prevMajorX) * fpp / rate;
                        if (Double.isNaN(stepSeconds)) {
                            stepSeconds = step;
                        }
                        assertEquals(stepSeconds, step, stepSeconds * 1e-6, "equal major spacing"); // x carries double rounding
                        assertTrue(inSequence(step), "major step " + step + " is not in the 1-2-5 sequence");
                    }
                    prevMajorX = t.x();
                    checkedMajors++;
                    // exact multiple of a sequence step in time
                    double seconds = (scroll + t.x() * fpp) / rate;
                    double nearest = bestStep(seconds);
                    assertTrue(nearest > 0, "major at " + seconds + " s is a multiple of no sequence step");
                }
                prevX = t.x();
            }
        }
        assertTrue(checkedMajors > 3000, "too few majors checked: " + checkedMajors);
    }

    private static boolean inSequence(double step) {
        for (double s : SEQUENCE) {
            if (Math.abs(step - s) <= s * 1e-6) {
                return true;
            }
        }
        return false;
    }

    /** Returns a sequence step of which {@code seconds} is an exact multiple, or 0. */
    private static double bestStep(double seconds) {
        for (double s : SEQUENCE) {
            double k = seconds / s;
            if (Math.abs(k - Math.rint(k)) <= 1e-6 * Math.max(1, Math.abs(k))) {
                return s;
            }
        }
        return 0;
    }

    @Test
    void majorStepIsTheSmallestThatKeeps80Pixels() {
        // 44100 Hz, 441 frames per pixel = 100 px per second: 0.5 s is 50 px (too close), 1 s is 100 px
        List<RulerTicks.Tick> t = RulerTicks.generate(new TimeAxis(44100, 441, 0), 400);
        assertEquals(100.0, t.stream().filter(RulerTicks.Tick::major).skip(1).findFirst().orElseThrow().x(), 1e-9);
        // 44100 Hz, 352.8 frames per pixel = 125 px per second: 0.5 s is 62.5 px, 1 s 125 px -> still 1 s
        // 44100 Hz, 220.5 frames per pixel = 200 px per second: 0.5 s is 100 px -> 0.5 s
        List<RulerTicks.Tick> u = RulerTicks.generate(new TimeAxis(44100, 220.5, 0), 400);
        assertEquals(100.0, u.stream().filter(RulerTicks.Tick::major).skip(1).findFirst().orElseThrow().x(), 1e-9);
        assertEquals("0.5", u.stream().filter(RulerTicks.Tick::major).skip(1).findFirst().orElseThrow().label());
        // 160 px per second exactly: 0.5 s = 80 px, which is allowed (at least 80)
        List<RulerTicks.Tick> v = RulerTicks.generate(new TimeAxis(44100, 275.625, 0), 400);
        assertEquals("0.5", v.stream().filter(RulerTicks.Tick::major).skip(1).findFirst().orElseThrow().label());
    }

    @Test
    void ticksAreImmutableAndArgumentsChecked() {
        List<RulerTicks.Tick> t = RulerTicks.generate(new TimeAxis(44100, 441, 0), 100);
        assertThrows(UnsupportedOperationException.class, () -> t.add(new RulerTicks.Tick(0, 0, true, "")));
        assertThrows(IllegalArgumentException.class, () -> RulerTicks.generate(new TimeAxis(44100, 441, 0), 0));
        assertThrows(NullPointerException.class, () -> RulerTicks.generate(null, 100));
    }
}
