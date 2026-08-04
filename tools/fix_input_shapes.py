"""Schreibt feste Eingabeformen in die openWakeWord-tflite-Modelle.

HINTERGRUND: Die Original-Modelle (github.com/dscripka/openWakeWord,
Releases v0.5.1) tragen dynamische Eingabe-Dimensionen (-1). Die
Android-Java-Runtime von TensorFlow Lite bereitet ein Modell schon im
Interpreter-KONSTRUKTOR vor (anders als die Python-Runtime, die bis
allocate_tensors() wartet) - mit -1 laeuft dabei die Groessenberechnung
ueber: "BytesRequired number of elements overflowed", Node CONV_2D
failed to prepare (live auf dem Galaxy aufgetreten, 22.07.2026). Ein
resizeInput() im Code kaeme zu spaet.

Dieses Skript patcht die Formen IN-PLACE im Flatbuffer (die
Vektorlaengen aendern sich nicht, nur die Werte) - kein TensorFlow und
kein flatc noetig, nur: pip install numpy flatbuffers tflite

Nutzung: python fix_input_shapes.py <ordner-mit-den-original-modellen>
Ergebnis: *_fixed.tflite daneben; diese Fassungen (umbenannt auf die
Originalnamen) liegen in app/src/main/assets/.

Nach jedem Patch die Pipeline gegen Testaudio verifizieren (Positiv-
Clip mit >=3 s Stille davor - die Puffer brauchen ~2,1 s Anlauf!).
"""
import sys

import tflite


def fixiere(pfad: str, ziel: str, neue_form: list) -> None:
    buf = bytearray(open(pfad, "rb").read())
    model = tflite.Model.GetRootAsModel(buf, 0)
    sg = model.Subgraphs(0)
    t = sg.Tensors(sg.Inputs(0))
    shape = t.ShapeAsNumpy()
    sig = t.ShapeSignatureAsNumpy()
    print(pfad, "vorher: shape", shape.tolist(), "signature", sig.tolist())
    assert shape.flags.writeable and sig.flags.writeable, "kein beschreibbarer View"
    assert len(shape) == len(neue_form) and len(sig) == len(neue_form)
    shape[:] = neue_form
    sig[:] = neue_form
    open(ziel, "wb").write(buf)
    print("  ->", ziel, "fixiert auf", neue_form)


if __name__ == "__main__":
    ordner = sys.argv[1] if len(sys.argv) > 1 else "."
    # 1760 = 1280 Samples (80-ms-Block) + 3*160 Fenster-Vorlauf, siehe
    # OpenWakeWord.kt (RAW_LEN).
    fixiere(f"{ordner}/melspectrogram.tflite",
            f"{ordner}/melspectrogram_fixed.tflite", [1, 1760])
    fixiere(f"{ordner}/embedding_model.tflite",
            f"{ordner}/embedding_model_fixed.tflite", [1, 76, 32, 1])
    # hey_jarvis_v0.1.tflite ist bereits komplett statisch ([1,16,96]).
