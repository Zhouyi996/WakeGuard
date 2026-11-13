import sys, zipfile, shutil
src_apk, dex, dst_apk = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copy(src_apk, dst_apk)
with zipfile.ZipFile(dst_apk, "a", zipfile.ZIP_DEFLATED) as z:
    names = z.namelist()
    if "classes.dex" in names:
        raise SystemExit("classes.dex already present")
    z.write(dex, "classes.dex")
print("dex injected ->", dst_apk)
