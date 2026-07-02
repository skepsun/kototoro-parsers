#!/usr/bin/env python3
"""
Fetch all Yealico/H-Viewer site rules from GitHub, parse, catalog,
test availability, and prepare for kototoro-parsers conversion.
"""

import json, os, sys, time, re, ssl, urllib.request, urllib.error

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
RULES_CACHE = os.path.join(PROJECT_DIR, "yealico_rules_cache")
PARSER_OUT = os.path.join(PROJECT_DIR, "src/main/kotlin/org/skepsun/kototoro/parsers/site/yealico")
RULES_RES = os.path.join(PROJECT_DIR, "src/main/resources/yealico_rules")

SOURCES = [
    ("H-Viewer-Sites/Index", "https://raw.githubusercontent.com/H-Viewer-Sites/Index/master/sites.json"),
    ("H-Viewer-Sites/zhihaofans", "https://raw.githubusercontent.com/H-Viewer-Sites/zhihaofans/master/sites.json"),
]

PROXY = "http://127.0.0.1:7890"
USE_PROXY = True

def http_get(url, timeout=30):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 YealicoFetcher/1.0"})
    if USE_PROXY:
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}),
            urllib.request.HTTPSHandler(context=ctx))
    else:
        opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx))
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.read().decode('utf-8-sig')
    except Exception as e:
        print(f"  ERR: {e}")
        return None

def fetch_sites():
    all_sites = []
    for src_name, url in SOURCES:
        print(f"Fetching {src_name}...")
        text = http_get(url)
        if not text: continue
        try:
            cats = json.loads(text)
            print(f"  {len(cats)} categories")
            for cat in cats:
                for s in cat.get("sites", []):
                    s["_source"] = src_name
                    s["_category"] = cat.get("title", "?")
                    all_sites.append(s)
        except Exception as e:
            print(f"  JSON error: {e}")
    return all_sites

def fetch_rule(site):
    url = site.get("json", "")
    if not url: return None
    url = url.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
    text = http_get(url)
    if not text and "/master/" in url:
        text = http_get(url.replace("/master/", "/main/"))
    if text:
        try: return json.loads(text)
        except: return None
    return None

def rule_type(rule):
    if not rule: return "unknown"
    gr = rule.get("galleryRule", {})
    if gr.get("videoRule"): return "video"
    if rule.get("detailRule", {}).get("chapterRule"): return "manga"
    if gr.get("pictureRule"): return "gallery"
    if rule.get("indexRule", {}).get("item", {}).get("path"): return "api_gallery"
    return "gallery"

def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch", action="store_true")
    ap.add_argument("--test", action="store_true")
    ap.add_argument("--convert", action="store_true")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--no-proxy", action="store_true")
    ap.add_argument("--max-test", type=int, default=40)
    args = ap.parse_args()
    if args.no_proxy: global USE_PROXY; USE_PROXY = False
    do_all = args.all or (not args.fetch and not args.test and not args.convert)

    if args.fetch or do_all:
        os.makedirs(RULES_CACHE, exist_ok=True)
        sites = fetch_sites()
        print(f"\nTotal sites: {len(sites)}")
        rules_summary = []
        for i, s in enumerate(sites):
            name = s.get("title", "?")
            print(f"[{i+1}/{len(sites)}] {name}...")
            rule = fetch_rule(s)
            if rule:
                safe = re.sub(r'[^\w\-_.]', '_', name)
                with open(os.path.join(RULES_CACHE, f"{safe}.json"), 'w') as f:
                    json.dump(rule, f, ensure_ascii=False, indent=2)
                rt = rule_type(rule)
                rules_summary.append({
                    "sid": s.get("sid"), "title": name, "author": s.get("author"),
                    "category": s.get("_category"), "source": s.get("_source"),
                    "type": rt, "description": s.get("description", ""),
                    "r18": s.get("r18", False), "lastUpdate": s.get("lastUpdate"),
                    "indexUrl": rule.get("indexUrl", ""), "flag": rule.get("flag", ""),
                    "cache_file": f"{safe}.json",
                })
                print(f"  -> {rt}")
            else:
                print(f"  -> FAILED")
            time.sleep(0.3)
        with open(os.path.join(RULES_CACHE, "_summary.json"), 'w') as f:
            json.dump(rules_summary, f, ensure_ascii=False, indent=2)
        print(f"\nSaved {len(rules_summary)} rules.")

    if args.test or do_all:
        sp = os.path.join(RULES_CACHE, "_summary.json")
        if not os.path.exists(sp):
            print("No summary. Run --fetch first."); return
        with open(sp) as f: summary = json.load(f)
        results = []
        for i, s in enumerate(summary[:args.max_test]):
            name = s["title"]; url = s.get("indexUrl", "")
            print(f"[{i+1}/{min(len(summary), args.max_test)}] Testing {name}")
            tu = url.replace("{page:1}","1").replace("{page:0}","0")
            tu = re.sub(r'\{[^}]+\}', 'test', tu)
            if "{json:" in url:
                ok, msg = True, "JSON API"
            elif tu.startswith("http"):
                t = http_get(tu, timeout=10)
                ok, msg = (True, f"OK {len(t)}B") if t and len(t)>50 else (False, f"Bad {len(t) if t else 0}B")
            else:
                ok, msg = False, "Bad URL"
            print(f"  {'OK' if ok else 'FAIL'} {msg}")
            results.append({"title": name, "url": url, "available": ok, "msg": msg})
            time.sleep(0.3)
        with open(os.path.join(RULES_CACHE, "_availability.json"), 'w') as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        ok_count = sum(1 for r in results if r["available"])
        print(f"\n{ok_count}/{len(results)} accessible")

    if args.convert or do_all:
        sp = os.path.join(RULES_CACHE, "_summary.json")
        if not os.path.exists(sp):
            print("No summary. Run --fetch first."); return
        with open(sp) as f: summary = json.load(f)

        os.makedirs(RULES_RES, exist_ok=True)
        copied = 0
        for s in summary:
            cf = s.get("cache_file", "")
            src = os.path.join(RULES_CACHE, cf)
            if os.path.exists(src):
                with open(src) as sf, open(os.path.join(RULES_RES, cf), 'w') as df:
                    df.write(sf.read())
                copied += 1
        print(f"Copied {copied} rules to {RULES_RES}")

        os.makedirs(PARSER_OUT, exist_ok=True)

        # Generate YealicoRuleParser.kt
        parser_code = open(os.path.join(os.path.dirname(__file__), "yealico_parser_template.kt")).read() if os.path.exists(os.path.join(os.path.dirname(__file__), "yealico_parser_template.kt")) else None
        if not parser_code:
            print("No template found, writing inline parser")
            parser_code = open(os.path.join(os.path.dirname(__file__), "..", "yealico_parser_template.kt")).read() if os.path.exists(os.path.join(os.path.dirname(__file__), "..", "yealico_parser_template.kt")) else None

        if not parser_code:
            # Fallback: generate a basic registry
            with open(os.path.join(PARSER_OUT, "YealicoParserRegistry.kt"), 'w') as f:
                f.write(generate_registry(summary))
            print(f"Generated YealicoParserRegistry.kt")

        # Copy rule JSONs info
        catalog = []
        for s in summary:
            catalog.append({
                "name": s["title"],
                "type": s["type"],
                "cacheFile": s.get("cache_file", ""),
                "indexUrl": s.get("indexUrl", ""),
                "r18": s.get("r18", False),
            })
        with open(os.path.join(RULES_RES, "_catalog.json"), 'w') as f:
            json.dump(catalog, f, ensure_ascii=False, indent=2)

        print(f"\nOutput in {PARSER_OUT}")

def generate_registry(summary):
    entries = []
    for s in summary:
        entries.append(f'        RuleEntry("{s["title"]}", "{s["type"]}", "{s.get("cache_file","")}", {str(s.get("r18", False)).lower()}),')

    type_counts = {}
    for s in summary:
        type_counts[s["type"]] = type_counts.get(s["type"], 0) + 1
    type_info = ", ".join(f"{k}={v}" for k, v in sorted(type_counts.items()))

    return f'''package org.skepsun.kototoro.parsers.site.yealico

/**
 * Registry of all Yealico site rule sources ({len(summary)} total: {type_info}).
 * Auto-generated by fetch_yealico_rules.py — do not edit by hand.
 */
object YealicoParserRegistry {{
    data class RuleEntry(
        val title: String,
        val type: String,
        val cacheFile: String,
        val r18: Boolean,
    )

    val ALL_RULES: List<RuleEntry> = listOf(
{chr(10).join(entries)}
    )

    val byType: Map<String, List<RuleEntry>> = ALL_RULES.groupBy {{ it.type }}
    val nonAdult: List<RuleEntry> = ALL_RULES.filter {{ !it.r18 }}
}}
'''

if __name__ == "__main__":
    main()
