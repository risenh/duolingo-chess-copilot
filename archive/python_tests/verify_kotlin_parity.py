"""Regressionsgatter der Python-Zwillingspipeline (Einstieg für die CI).

Historie: Früher lief hier eine eigene Kopie aus "Negativbeispiele abweisen und Positivbeispiele feldweise gegen das FEN prüfen";
seit comb_locator und test_full_pipeline_v2 nur noch dünn an grid_calibrate.locate_board delegieren,
durchliefen dieses Skript und verify_ground_truth_diff dieselbe langsame Feinkalibrierung über dieselben Frames
(doppelte Laufzeit in der CI ohne zusätzliche Aussage). Deshalb wird derselbe Einstieg wiederverwendet, es gibt genau eine Quelle.

Zur Abgrenzung: Dieses Gatter prüft, ob die Python-Zwillingspipeline mit den von Hand bestimmten Sollwerten übereinstimmt;
die tatsächliche Übereinstimmung zur Kotlin-Laufzeit belegt erst die Telemetrie aus Stufe 4 (Confidence und Residuum auf dem Gerät).
"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def run_parity_verification():
    from tools.verify_ground_truth_diff import run_ground_truth_diff_verification
    run_ground_truth_diff_verification()


if __name__ == '__main__':
    run_parity_verification()
