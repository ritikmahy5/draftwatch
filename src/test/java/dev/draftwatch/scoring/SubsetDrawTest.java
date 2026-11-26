package dev.draftwatch.scoring;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SubsetDrawTest {
  @Test
  public void drawIsSortedDistinctAndInRange() {
    List<Subset> subsets = SubsetDraw.draw(1000, 25, 500, 25);
    assertEquals(500, subsets.size());
    for (int k = 0; k < subsets.size(); k++) {
      Subset s = subsets.get(k);
      assertEquals(25, s.size());
      assertEquals(k, s.ordinal());
      int[] idx = s.indices();
      assertEquals(25, idx.length);
      for (int i = 0; i < idx.length; i++) {
        assertTrue(idx[i] >= 0 && idx[i] < 1000);
        if (i > 0) {
          assertTrue("ascending and distinct", idx[i] > idx[i - 1]);
        }
      }
    }
  }

  @Test
  public void drawIsDeterministic() {
    List<Subset> a = SubsetDraw.draw(1000, 50, 20, 50);
    List<Subset> b = SubsetDraw.draw(1000, 50, 20, 50);
    for (int k = 0; k < 20; k++) {
      assertArrayEquals(a.get(k).indices(), b.get(k).indices());
    }
  }

  @Test
  public void drawGolden() {
    List<Subset> s = SubsetDraw.draw(1000, 25, 2, 25);
    // Pasted from the first run: this pins determinism; the other tests pin correctness.
    assertArrayEquals(
        new int[] {
          19, 27, 31, 53, 68, 161, 202, 340, 365, 381, 392, 394, 488, 544, 580, 593, 720, 733, 757,
          760, 880, 904, 981, 994, 998
        },
        s.get(0).indices());
    assertArrayEquals(
        new int[] {
          1, 8, 44, 93, 265, 316, 319, 342, 374, 407, 417, 424, 432, 433, 496, 536, 610, 614, 664,
          726, 749, 788, 810, 812, 867
        },
        s.get(1).indices());
  }

  @Test
  public void fullDrawIsTheIdentity() {
    int[] all = SubsetDraw.draw(5, 5, 1, 3).get(0).indices();
    assertArrayEquals(new int[] {0, 1, 2, 3, 4}, all);
  }

  @Test
  public void indicesAreADefensiveCopy() {
    Subset s = SubsetDraw.draw(10, 3, 1, 1).get(0);
    int[] first = s.indices();
    first[0] = 99;
    assertNotSame(first, s.indices());
    assertTrue(s.indices()[0] != 99);
  }

  @Test
  public void rejectsNLargerThanPool() {
    assertThrows(IllegalArgumentException.class, () -> SubsetDraw.draw(10, 11, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> SubsetDraw.draw(10, 0, 1, 0));
  }
}
