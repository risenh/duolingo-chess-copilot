import cv2
import os
import shutil

def export_all_verified_templates():
    out_dir = "dulo/app/src/main/assets/templates"
    
    # Alte Vorlagen entfernen
    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    
    class_samples = {
        'P': [
            "scratch/all_cells/img1/r6_c0.png", "scratch/all_cells/img1/r6_c1.png", "scratch/all_cells/img1/r6_c4.png", "scratch/all_cells/img1/r6_c6.png",
            "scratch/all_cells/img1/r1_c0.png", "scratch/all_cells/img1/r1_c3.png", "scratch/all_cells/img1/r1_c6.png", "scratch/all_cells/img1/r1_c7.png",
            "scratch/all_cells/img2/r1_c0.png", "scratch/all_cells/img2/r1_c1.png", "scratch/all_cells/img2/r1_c5.png", "scratch/all_cells/img2/r1_c6.png", "scratch/all_cells/img2/r3_c2.png",
            "scratch/all_cells/img2/r6_c0.png", "scratch/all_cells/img2/r6_c4.png", "scratch/all_cells/img2/r6_c6.png", "scratch/all_cells/img2/r6_c7.png",
            "scratch/all_cells/img3/r6_c0.png", "scratch/all_cells/img3/r6_c3.png", "scratch/all_cells/img3/r6_c6.png", "scratch/all_cells/img3/r6_c7.png",
            "scratch/all_cells/img3/r1_c0.png", "scratch/all_cells/img3/r1_c4.png", "scratch/all_cells/img3/r1_c6.png", "scratch/all_cells/img3/r1_c7.png"
        ],
        'R': [
            "scratch/all_cells/img1/r7_c0.png", "scratch/all_cells/img1/r7_c7.png",
            "scratch/all_cells/img1/r0_c0.png", "scratch/all_cells/img1/r0_c7.png",
            "scratch/all_cells/img2/r0_c0.png", "scratch/all_cells/img2/r0_c7.png",
            "scratch/all_cells/img2/r7_c0.png", "scratch/all_cells/img2/r7_c7.png",
            "scratch/all_cells/img3/r7_c0.png", "scratch/all_cells/img3/r7_c7.png",
            "scratch/all_cells/img3/r0_c0.png", "scratch/all_cells/img3/r0_c7.png"
        ],
        'N': [
            "scratch/all_cells/img1/r7_c1.png", "scratch/all_cells/img1/r7_c6.png",
            "scratch/all_cells/img1/r0_c1.png", "scratch/all_cells/img1/r0_c6.png",
            "scratch/all_cells/img2/r0_c1.png", "scratch/all_cells/img2/r0_c6.png",
            "scratch/all_cells/img2/r7_c1.png", "scratch/all_cells/img2/r7_c6.png",
            "scratch/all_cells/img3/r7_c1.png", "scratch/all_cells/img3/r7_c6.png",
            "scratch/all_cells/img3/r0_c1.png", "scratch/all_cells/img3/r0_c6.png"
        ],
        'B': [
            "scratch/all_cells/img1/r7_c2.png", "scratch/all_cells/img1/r7_c5.png",
            "scratch/all_cells/img1/r0_c2.png", "scratch/all_cells/img1/r0_c5.png",
            "scratch/all_cells/img2/r0_c2.png", "scratch/all_cells/img2/r0_c5.png",
            "scratch/all_cells/img2/r7_c2.png", "scratch/all_cells/img2/r7_c5.png",
            "scratch/all_cells/img3/r7_c2.png", "scratch/all_cells/img3/r7_c5.png",
            "scratch/all_cells/img3/r0_c2.png", "scratch/all_cells/img3/r0_c5.png"
        ],
        'Q': [
            "scratch/all_cells/img1/r7_c3.png", "scratch/all_cells/img1/r0_c3.png",
            "scratch/all_cells/img2/r0_c4.png", "scratch/all_cells/img2/r7_c4.png",
            "scratch/all_cells/img3/r7_c3.png", "scratch/all_cells/img3/r0_c3.png"
        ],
        'K': [
            "scratch/all_cells/img1/r7_c4.png", "scratch/all_cells/img1/r0_c4.png",
            "scratch/all_cells/img2/r0_c3.png", "scratch/all_cells/img2/r7_c3.png",
            "scratch/all_cells/img3/r7_c4.png", "scratch/all_cells/img3/r0_c4.png"
        ]
    }
    
    total = 0
    for cls_name, paths in class_samples.items():
        idx = 0
        for src_path in paths:
            if os.path.exists(src_path):
                img = cv2.imread(src_path)
                if img is not None:
                    resized = cv2.resize(img, (48, 48))
                    gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
                    out_path = os.path.join(out_dir, f"{cls_name}_{idx}.png")
                    cv2.imwrite(out_path, gray)
                    total += 1
                    idx += 1
                    
    print(f"{total} in der Praxis geprüfte Vorlagen nach {out_dir} exportiert")

if __name__ == '__main__':
    export_all_verified_templates()
