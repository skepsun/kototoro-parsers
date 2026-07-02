#!/usr/bin/env python3
"""
Full availability test for all 103 Yealico site rules.
Tests: HTTP access + selector validity on actual page content.
"""
import json, os, re, ssl, sys, time, urllib.request, urllib.error
from html.parser import HTMLParser
from collections import Counter

RULES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "yealico_rules_cache")
PROXY = "http://127.0.0.1:7890"
TIMEOUT = 15

# ── HTTP client ──────────────────────────────────────────────
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = False
opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}),
    urllib.request.HTTPSHandler(context=ctx),
)

def http_get(url, timeout=TIMEOUT):
    """Fetch URL, return (status_code, body, error_msg)."""
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    })
    try:
        with opener.open(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="ignore")
            return resp.status, body, None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="ignore")[:2000], str(e)
    except Exception as e:
        return None, None, str(e)[:120]

# ── Selector test ────────────────────────────────────────────
def test_selector(html, selector_json):
    """Return count of elements matched by a Yealico-style selector object."""
    if not selector_json or not html:
        return 0, "no_selector"
    sel = selector_json.get("selector", "")
    path = selector_json.get("path", "")
    if not sel and not path:
        return 0, "empty"
    
    # HTML CSS selector matching — use simple string counting for <tag + class/id patterns
    if sel:
        # Convert CSS selector to simple grep-able patterns
        # .class -> class="...class..."
        # #id -> id="id"
        # tag -> <tag
        # tag.class -> <tag class="...class..."
        pat = sel
        # Very basic: count matches of key structural parts
        parts = re.split(r'\s+', sel.strip())
        count = 0
        for part in parts:
            part = part.strip()
            if not part:
                continue
            # Handle 'tag.class' pattern
            tag_cls = re.match(r'^(\w+)\.(.+)$', part)
            tag_id = re.match(r'^(\w+)#(.+)$', part)
            if tag_cls:
                tag, cls = tag_cls.groups()
                count = len(re.findall(rf'<{tag}[^>]*class=["\'][^"\']*\b{cls}\b[^"\']*["\']', html, re.I))
                if count > 0:
                    break
            elif tag_id:
                tag, id_ = tag_id.groups()
                count = len(re.findall(rf'<{tag}[^>]*id=["\']{id_}["\']', html, re.I))
                if count > 0:
                    break
            elif part.startswith("."):
                cls = part[1:]
                count = len(re.findall(rf'class=["\'][^"\']*\b{cls}\b[^"\']*["\']', html, re.I))
                if count > 0:
                    break
            elif part.startswith("#"):
                id_ = part[1:]
                count = html.count(f'id="{id_}"') + html.count(f"id='{id_}'")
                if count > 0:
                    break
            elif re.match(r'^\w+$', part):
                # plain tag
                count = len(re.findall(rf'<{part}\b', html, re.I))
                if count > 0:
                    break
            else:
                # Complex selectors like 'div.pic_box a img' — just check the first meaningful part
                first_tag = re.match(r'^(\w+)', part)
                if first_tag:
                    count = len(re.findall(rf'<{first_tag.group(1)}\b', html, re.I))
                    break
        
        if count > 0:
            return count, f"CSS({sel[:60]}):{count}"
        return 0, f"CSS({sel[:60]}):0"
    
    if path:
        # JSONPath in HTML? Could be JSON body
        if html.strip().startswith("{"):
            try:
                obj = json.loads(html)
                # Try to navigate
                keys = path.replace("$.", "").split(".")
                cur = obj
                for k in keys:
                    if isinstance(cur, dict):
                        cur = cur.get(k)
                    elif isinstance(cur, list) and len(cur) > 0:
                        cur = cur[0].get(k) if isinstance(cur[0], dict) else None
                    else:
                        cur = None
                        break
                if cur is not None:
                    arr = cur if isinstance(cur, list) else [cur]
                    return len(arr), f"JSONPath({path}):{len(arr)}"
                return 0, f"JSONPath({path}):not_found"
            except:
                return 0, f"JSONPath({path}):parse_err"
        return 0, f"JSONPath({path}):not_json"
    
    return 0, "unknown"

# ── URL builder ──────────────────────────────────────────────
def build_test_url(rule):
    url = rule.get("indexUrl", "")
    if not url:
        return None
    
    # Expand page placeholder
    url = re.sub(r'\{page:\d+\}', '1', url)
    url = re.sub(r'\{pageStr:page/\{page:\d+\}\}', '1', url)
    
    # Replace remaining simple placeholders with 'test'
    url = re.sub(r'\{[^}]+\}', 'test', url)
    
    # Handle JSON body placeholders
    if "{json:" in url:
        # Extract the JSON body from the URL
        m = re.search(r'\{json:(.+?)\}\s*$', url)
        if m:
            base_url = url[:m.start()]
            json_body = m.group(1)
            return base_url  # Return just the base for GET
        return url.split("{json:")[0].rstrip("&")
    
    return url

# ── Main test loop ───────────────────────────────────────────
def main():
    summary_path = os.path.join(RULES_DIR, "_summary.json")
    if not os.path.exists(summary_path):
        print("No _summary.json. Run fetch first.")
        sys.exit(1)
    
    with open(summary_path, encoding="utf-8") as f:
        summary = json.load(f)
    
    results = []
    stats = Counter()
    
    print(f"Testing {len(summary)} sites...")
    print(f"{'='*90}")
    print(f"{'#':>3} {'Site':<30} {'Type':<12} {'HTTP':>5} {'Items':>6} {'Status'}")
    print(f"{'-'*90}")
    
    for i, entry in enumerate(summary):
        name = entry["title"]
        rtype = entry.get("type", "?")
        cache_file = entry.get("cache_file", "")
        
        # Load actual rule
        rule_path = os.path.join(RULES_DIR, cache_file)
        if not os.path.exists(rule_path):
            results.append({"title": name, "type": rtype, "http_code": None, "items": 0, "status": "RULE_MISSING"})
            stats["RULE_MISSING"] += 1
            print(f"{i+1:>3} {name:<30} {rtype:<12} {'N/A':>5} {'':>6} RULE_MISSING")
            continue
        
        with open(rule_path, encoding="utf-8-sig") as f:
            try:
                rule = json.load(f)
            except:
                results.append({"title": name, "type": rtype, "http_code": None, "items": 0, "status": "JSON_ERROR"})
                stats["JSON_ERROR"] += 1
                print(f"{i+1:>3} {name:<30} {rtype:<12} {'N/A':>5} {'':>6} JSON_ERROR")
                continue
        
        # Build URL
        test_url = build_test_url(rule)
        if not test_url:
            results.append({"title": name, "type": rtype, "http_code": None, "items": 0, "status": "NO_URL"})
            stats["NO_URL"] += 1
            print(f"{i+1:>3} {name:<30} {rtype:<12} {'N/A':>5} {'':>6} NO_URL")
            continue
        
        # JSON API detection
        is_json_api = "{json:" in rule.get("indexUrl", "")
        index_rule = rule.get("indexRule", {})
        item_sel = index_rule.get("item", {})
        is_jsonpath = bool(item_sel.get("path")) and not bool(item_sel.get("selector"))
        
        status_str = ""
        
        if is_json_api or is_jsonpath:
            # JSON API — mark as SPECIAL (needs POST/special handling)
            results.append({"title": name, "type": rtype, "http_code": "JSON_API", "items": "N/A", "status": "JSON_API"})
            stats["JSON_API"] += 1
            status_str = "JSON_API"
            print(f"{i+1:>3} {name:<30} {rtype:<12} {'JSON':>5} {'N/A':>6} JSON_API (POST/JSON)")
        elif test_url.startswith("http"):
            # Actual HTTP request
            code, body, err = http_get(test_url)
            
            if err:
                # Categorize the error
                err_lower = str(err).lower()
                if "403" in err_lower:
                    stats["403"] += 1
                    status_str = "403 Forbidden"
                elif "404" in err_lower:
                    stats["404"] += 1
                    status_str = "404 Not Found"
                elif "502" in err_lower or "bad gateway" in err_lower:
                    stats["502"] += 1
                    status_str = "502 Bad Gateway"
                elif "429" in err_lower:
                    stats["429"] += 1
                    status_str = "429 Rate Limited"
                elif "timeout" in err_lower or "timed out" in err_lower:
                    stats["TIMEOUT"] += 1
                    status_str = "TIMEOUT"
                elif "ssl" in err_lower or "certificate" in err_lower:
                    stats["SSL_ERR"] += 1
                    status_str = "SSL_ERROR"
                elif "eof" in err_lower:
                    stats["EOF"] += 1
                    status_str = "EOF"
                else:
                    stats["HTTP_ERR"] += 1
                    status_str = f"ERR: {err[:50]}"
                
                results.append({"title": name, "type": rtype, "http_code": code, "items": 0, "status": status_str, "url": test_url[:120]})
                print(f"{i+1:>3} {name:<30} {rtype:<12} {str(code):>5} {'':>6} {status_str}")
            else:
                # Got HTML — test selector
                items_count = 0
                item_info = ""
                
                if index_rule and item_sel:
                    items_count, item_info = test_selector(body, item_sel)
                
                if items_count > 0:
                    stats["OK"] += 1
                    status_str = f"OK items={items_count}"
                    print(f"{i+1:>3} {name:<30} {rtype:<12} {str(code):>5} {items_count:>6} {status_str}")
                elif len(body) > 200:
                    stats["SEL_FAIL"] += 1
                    status_str = f"HTML({len(body)}B) SEL:0 — {item_info}"
                    print(f"{i+1:>3} {name:<30} {rtype:<12} {str(code):>5} {0:>6} {status_str}")
                else:
                    stats["EMPTY"] += 1
                    status_str = f"HTML({len(body)}B) — too short"
                    print(f"{i+1:>3} {name:<30} {rtype:<12} {str(code):>5} {'':>6} {status_str}")
                
                results.append({
                    "title": name, "type": rtype, "http_code": code,
                    "items": items_count, "item_info": item_info,
                    "status": status_str, "url": test_url[:120],
                    "body_size": len(body),
                })
        else:
            results.append({"title": name, "type": rtype, "http_code": None, "items": 0, "status": "BAD_URL", "url": test_url})
            stats["BAD_URL"] += 1
            print(f"{i+1:>3} {name:<30} {rtype:<12} {'N/A':>5} {'':>6} BAD_URL: {test_url[:60]}")
        
        time.sleep(0.15)  # Rate limit
    
    # ── Summary ──────────────────────────────────────────────
    print(f"\n{'='*90}")
    print(f"\nRESULTS SUMMARY")
    print(f"{'─'*50}")
    
    fully_ok = stats.get("OK", 0)
    json_api = stats.get("JSON_API", 0)
    total_usable = fully_ok + json_api
    
    print(f"  ✅ Fully working (HTML+selector match):  {fully_ok}")
    print(f"  🔧 JSON API (needs POST/special handling): {json_api}")
    print(f"  📊 Total usable:                           {total_usable}")
    print(f"  {'─'*40}")
    
    for k, v in sorted(stats.items()):
        if k not in ("OK", "JSON_API"):
            print(f"  ❌ {k}: {v}")
    
    # Save detailed results
    out_path = os.path.join(RULES_DIR, "_full_test_results.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({
            "summary": dict(stats),
            "total_usable": total_usable,
            "total_tested": len(summary),
            "results": results,
        }, f, ensure_ascii=False, indent=2)
    
    print(f"\nDetailed results saved to {out_path}")
    
    # Print working sites
    print(f"\n{'='*90}")
    print(f"WORKING SITES ({fully_ok}):")
    for r in results:
        if r["status"].startswith("OK "):
            print(f"  ✅ {r['title']:<30} [{r['type']:<12}] {r['status']}")
    
    print(f"\nJSON API SITES ({json_api}):")
    for r in results:
        if r["status"] == "JSON_API":
            print(f"  🔧 {r['title']:<30} [{r['type']:<12}]")

if __name__ == "__main__":
    main()
