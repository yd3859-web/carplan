# -*- coding: utf-8 -*-
"""
D7 기억장치 수집 (정원 2026-11-09): 한국관광공사 TourAPI의 "여행코스"(contentTypeId=25)를 받아
phone-app/src/main/assets/courses.txt(여행상식.md 형식)로 만든다. 지금 파일은 손으로 정리한 30곳 —
이 스크립트가 성공하면 그 결과로 교체·확장한다(손 정리 줄은 보존하고 뒤에 덧붙임).

쓰는 법 (키는 정원이 공공데이터포털 data.go.kr에서 "한국관광공사_국문 관광정보 서비스" 활용신청):
  1) local.properties 에 TOURAPI_KEY=일반인증키(Decoding)  또는 환경변수 TOURAPI_KEY
  2) python3 tools/fetch_courses.py
주의: 2026-11-09 기준 키가 없어 실행은 못 해 봄. 엔드포인트가 다르면 BASE/파라미터만 고치면 된다.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://apis.data.go.kr/B551011/KorService2"
OUT = os.path.join(os.path.dirname(__file__), "..", "phone-app", "src", "main", "assets", "courses.txt")


def api_key():
    k = os.environ.get("TOURAPI_KEY")
    if k:
        return k
    lp = os.path.join(os.path.dirname(__file__), "..", "local.properties")
    if os.path.exists(lp):
        for line in open(lp, encoding="utf-8"):
            if line.startswith("TOURAPI_KEY="):
                return line.split("=", 1)[1].strip()
    sys.exit("TOURAPI_KEY가 없어요 — local.properties나 환경변수에 넣어 주세요")


def call(path, **params):
    q = {"serviceKey": api_key(), "MobileOS": "ETC", "MobileApp": "carplan", "_type": "json", "numOfRows": 200, **params}
    url = f"{BASE}/{path}?" + urllib.parse.urlencode(q)
    with urllib.request.urlopen(url, timeout=30) as r:
        body = json.load(r)
    items = body["response"]["body"]["items"]
    return items["item"] if items else []


def clean(s):
    s = re.sub(r"<[^>]+>", " ", s or "")
    return re.sub(r"\s+", " ", s).strip()


def main():
    # 1) 시·군 이름표: areaCode2(도) → 하위 sigungu
    areas = call("areaCode2")
    region_name = {}
    for a in areas:
        for sg in call("areaCode2", areaCode=a["code"]):
            region_name[(str(a["code"]), str(sg["code"]))] = sg["name"]
        time.sleep(0.2)

    # 2) 여행코스 목록(전국) → 코스마다 들르는 곳(detailInfo2의 subname)
    lines = []
    courses = call("areaBasedList2", contentTypeId=25, arrange="Q")  # 인기순
    for c in courses[:300]:
        cid = c["contentid"]
        region = region_name.get((str(c.get("areacode")), str(c.get("sigungucode"))), "").strip()
        if not region:
            continue
        # "남구" 같은 구 단위는 어느 도시인지 알 수 없어 뺀다 (차 여행 코스는 시·군 단위가 맞다).
        if region.endswith("구"):
            continue
        subs = call("detailInfo2", contentId=cid, contentTypeId=25)
        places = [clean(s.get("subname")) for s in subs if clean(s.get("subname"))]
        # 괄호 꼬리표 제거: "죽녹원(전남 담양)" → "죽녹원"
        places = [re.sub(r"\([^)]*\)$", "", p).strip() for p in places]
        if len(places) < 2:
            continue
        title = clean(c.get("title"))
        # 종류 태그는 비워 둔다(장소 이름으로 Taste.kindOf가 가늠) — 손 정리 줄은 태그가 있다.
        lines.append(f"{region}||{';'.join(places[:5])}|{title}")
        time.sleep(0.2)

    # 손 정리 부분은 두고, 지난 TourAPI 부분은 지우고 새로 쓴다 (다시 돌려도 중복 없음).
    MARK = "# --- TourAPI 여행코스 (fetch_courses.py) ---"
    kept = []
    for l in open(OUT, encoding="utf-8"):
        if l.rstrip("\n") == MARK:
            break
        kept.append(l.rstrip("\n"))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(kept) + "\n" + MARK + "\n" + "\n".join(lines) + "\n")
    print(f"{len(lines)}개 코스 추가 → {OUT}")


if __name__ == "__main__":
    main()
