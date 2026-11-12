//
// Post-fix verification probe for ALL IndStream LinkNaming language-naming bugs.
// Mirrors the FIXED Kotlin source. Run with: node naming_probe_test.js
// Expectation: every check PASSes.
//

const LANGUAGE_NAMES = new Set([
  "Hindi", "Urdu", "Bengali", "Arabic", "Russian", "Chinese", "Japanese", "Korean",
  "French", "Spanish", "Portuguese", "Filipino", "Indonesian", "Malaysian", "Vietnamese",
  "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Marathi", "Punjabi", "Gujarati",
  "Nepali", "Sinhala", "Thai", "Turkish", "German", "Italian", "Dutch", "Polish",
]);

const LANGUAGE_MODIFIERS = new Set([
  "audio", "dub", "dubbed", "version", "track", "sub", "subs", "subtitle", "subtitles",
]);

// FIXED regex (matches edited LinkNaming.kt): includes ; : tab, hyphen literal at end
const LANGUAGE_SPLIT = /[+,&/|.:;_ \t-]|\band\b|\bdual\b|\bmulti\b|\s+/gi;
// OLD buggy regex (for before/after contrast)
const LANGUAGE_SPLIT_OLD = /[+,&/|.\-_]|\band\b|\bdual\b|\bmulti\b|\s+/gi;

const MULTI_MARKER = /\b(?:multi|dual|both)(?:\s*-?\s*audio)?\b/i;
const SUBTITLE_LINE = /\b(subtitles?|subs|cc)\b/i;

const RESOLUTION_TOKEN = /\s*(?:\d{3,4}\s*p(?:\d{2,3})?|[24]\s*k)\b/gi;
const QUALITY_ALIAS = /(?:\s*\b(?:FHD|UHD|QHD)\b)|(?:\s+\bHD\b(?=\s*$))/gi;
const TRAILING_LANGUAGE = /(?:[\s,+/&()\[\]{}-]*\b(?:hindi|english|tamil|telugu|malayalam|kannada|bengali|bangla|marathi|punjabi|gujarati|urdu|nepali|sinhala|arabic|russian|chinese|mandarin|japanese|korean|french|spanish|portuguese|filipino|tagalog|indonesian|malaysian|vietnamese|thai|turkish|german|italian|dutch|polish|multi|dual)\b(?:\s*[(\[]?\s*(?:audio|dub|dubbed)\s*[)\]]?)?[\s)\]}]*)+$/i;

function orBlank(v) { return v == null || v === "" ? "" : v; }
function trimChars(s, chars) {
  let start = 0, end = s.length;
  while (start < end && chars.includes(s[start])) start++;
  while (end > start && chars.includes(s[end - 1])) end--;
  return s.slice(start, end);
}

function canonicalSubtitleName(raw) {
  const s = orBlank(raw).trim();
  if (!s) return "Subtitle";
  const lower = s.toLowerCase();
  if (lower.includes("हिन्द") || lower.includes("हिंद") || lower.includes("देवनागरी") ||
      lower.includes("devanagari") || lower === "hi" || lower === "hin" || lower.includes("hindi")) return "Hindi";
  if (lower.includes("اُردُو") || lower.includes("اردو") || lower.includes("urdu") ||
      lower === "ur" || lower === "urd") return "Urdu";
  if (lower.includes("বাংলা") || lower.includes("bengali") || lower.includes("bangla") ||
      lower === "bn" || lower === "ben" || lower === "bang") return "Bengali";
  if (lower.includes("العربية") || lower.includes("عرب") || lower.includes("arabic") ||
      lower === "ar" || lower === "ara" || lower === "arb") return "Arabic";
  if (lower.includes("русск") || lower.includes("russian") || lower === "ru" || lower === "rus") return "Russian";
  if (lower.includes("中文") || lower.includes("mandarin") || lower.includes("chinese") ||
      lower === "zh" || lower === "chi" || lower === "zho") return "Chinese";
  if (lower.includes("日本語") || lower.includes("日本") || lower.includes("japanese") ||
      lower === "ja" || lower === "jp" || lower === "jpn") return "Japanese";
  if (lower.includes("한국어") || lower.includes("한국") || lower.includes("korean") ||
      lower === "ko" || lower === "kor") return "Korean";
  if (lower.includes("français") || lower.includes("francais") || lower.includes("french") ||
      lower === "fr" || lower === "fre" || lower === "fra") return "French";
  if (lower.includes("español") || lower.includes("espanol") || lower.includes("spanish") ||
      lower === "es" || lower === "spa") return "Spanish";
  if (lower.includes("português") || lower.includes("portugues") || lower.includes("portuguese") ||
      lower === "pt" || lower === "por") return "Portuguese";
  if (lower.includes("filipino") || lower.includes("tagalog") || lower === "fil") return "Filipino";
  if (lower.includes("indonesian") || lower.includes("bahasa") || lower === "id" || lower === "ind") return "Indonesian";
  if (lower.includes("malaysian") || lower === "ms" || lower === "may" || lower === "msa") return "Malaysian";
  if (lower.includes("vietnamese") || lower === "vi" || lower === "vie") return "Vietnamese";
  if (lower.includes("english") || lower === "en" || lower === "eng") return "English";
  if (lower.includes("தமிழ") || lower.includes("tamil") || lower === "ta" || lower === "tam") return "Tamil";
  if (lower.includes("తెలుగు") || lower.includes("telugu") || lower === "te" || lower === "tel") return "Telugu";
  if (lower.includes("മലയാള") || lower.includes("malayalam") || lower === "ml" || lower === "mal") return "Malayalam";
  if (lower.includes("ಕನ್ನಡ") || lower.includes("kannada") || lower === "kn" || lower === "kan") return "Kannada";
  if (lower.includes("मराठ") || lower.includes("marathi") || lower === "mr" || lower === "mar") return "Marathi";
  if (lower.includes("ਪੰਜਾਬ") || lower.includes("punjabi") || lower === "pa" || lower === "pan") return "Punjabi";
  if (lower.includes("ગુજરાત") || lower.includes("gujarati") || lower === "gu" || lower === "guj") return "Gujarati";
  if (lower.includes("नेपाल") || lower.includes("nepali") || lower === "ne" || lower === "nep") return "Nepali";
  if (lower.includes("සිංහල") || lower.includes("sinhala") || lower.includes("singhalese") ||
      lower === "si" || lower === "sin") return "Sinhala";
  if (lower.includes("ไทย") || lower.includes("thai") || lower === "th" || lower === "tha") return "Thai";
  if (lower.includes("turkish") || lower === "tr" || lower === "tur") return "Turkish";
  if (lower.includes("german") || lower === "de" || lower === "ger" || lower === "deu") return "German";
  if (lower.includes("italian") || lower === "it" || lower === "ita") return "Italian";
  if (lower.includes("dutch") || lower === "nl" || lower === "dut" || lower === "nld") return "Dutch";
  if (lower.includes("polish") || lower === "pl" || lower === "pol") return "Polish";
  if (lower.includes("hindi dubbed") || lower === "dubbed") return "Hindi";
  // FIXED: return full trimmed string instead of truncating to first word
  return s;
}

function languageNames(raw, splitRegex = LANGUAGE_SPLIT) {
  const s = orBlank(raw).trim();
  if (!s) return [];
  const out = [];
  const seen = new Set();
  const chunks = s.split(splitRegex).filter(c => c !== "");
  for (const chunk of chunks) {
    let c = trimChars(chunk, " \t\n\r-.:;()[]{}".split(""));
    c = c.trim();
    if (c.length < 2 || LANGUAGE_MODIFIERS.has(c.toLowerCase())) continue;
    const canonical = canonicalSubtitleName(c);
    if (LANGUAGE_NAMES.has(canonical) && !seen.has(canonical)) {
      seen.add(canonical);
      out.push(canonical);
    }
  }
  return out;
}

function languageLabel(raw, originalLang, splitRegex = LANGUAGE_SPLIT) {
  const s = orBlank(raw).trim();
  if (!s) return originalLang ? languageLabel(originalLang, null, splitRegex) : "";
  if (s.toLowerCase() === "original" || s.toLowerCase() === "orig" || s.toLowerCase() === "org") {
    const r = originalLang ? languageLabel(originalLang, null, splitRegex) : "";
    return r || "Original";
  }
  const names = languageNames(s, splitRegex);
  if (names.length === 1) return names[0];
  if (names.length > 1) return names.join(", ");
  if (MULTI_MARKER.test(s)) return "Multi";
  return "";
}

// ---- FIXED: streamAudioLabel ----
// OLD: dropped entire lines containing subtitles/subs/cc, then fell back to stream name
// FIXED: drops only the subtitle clause (pipe/semicolon segment); keeps audio languages on the
//         same line; never falls back to the name badge.
function streamAudioLabelFixed(source) {
  const SUBTITLE_LINE_RE = /\b(subtitles?|subs|cc)\b/i;
  const SEG_CLAUSE_RE = /\s*[|;]\s*/;
  const detailLines = [];
  for (const f of [source.title, source.description]) {
    if (f) for (const line of f.split(/\n/)) {
      if (SUBTITLE_LINE_RE.test(line)) {
        const kept = line.split(SEG_CLAUSE_RE)
          .filter(seg => !SUBTITLE_LINE_RE.test(seg) && seg.trim())
          .join(", ");
        if (kept) detailLines.push(kept);
      } else if (line.trim()) {
        detailLines.push(line);
      }
    }
  }
  const detail = detailLines.join("\n");

  // FIXED: handle JSON array of strings or objects by extracting "name" field
  let languagesStr = orBlank(source.languages);
  if (languagesStr.startsWith("[")) {
    try {
      const arr = JSON.parse(languagesStr);
      const parts = [];
      for (const value of arr) {
        let str = "";
        if (typeof value === "string") str = value.trim();
        else if (value && typeof value === "object" && value.name) str = value.name.trim();
        if (str) parts.push(str);
      }
      languagesStr = parts.join(", ");
    } catch (_) { /* keep raw */ }
  }

  const declared = [languagesStr, detail].filter(x => x && x.trim()).join("\n");
  return languageLabel(declared);
}

// OLD streamAudioLabel: drops entire lines with subtitle words; falls back to name
function streamAudioLabelOld(source) {
  const detailLines = [];
  for (const f of [source.title, source.description]) {
    if (f) for (const line of f.split(/\n/)) {
      if (!SUBTITLE_LINE.test(line)) detailLines.push(line);
    }
  }
  const detail = detailLines.join("\n");
  const declared = [orBlank(source.languages), detail].filter(x => x && x.trim()).join("\n");
  const label = languageLabel(declared);
  return label || languageLabel(source.name) || ""; // OLD: falls back to name
}

// ---- FIXED: displayLanguageNames ----
function displayLanguageNamesFixed(audioLabel, mediaLabel) {
  const audio = languageNames(audioLabel);
  const media = languageNames(mediaLabel);
  if (audio.length >= 2 && audio.length >= media.length) return audio;
  if (media.length > audio.length) return media;
  if (audio.length) return audio;
  if (media.length && languageLabel(audioLabel) === "") return media;
  return [];
}

function displayLanguageNamesOld(audioLabel, mediaLabel) {
  const audio = languageNames(audioLabel);
  const media = languageNames(mediaLabel);
  if (audio.length >= 2) return audio; // OLD BUG
  if (media.length > audio.length) return media;
  if (audio.length) return audio;
  if (media.length && languageLabel(audioLabel) === "") return media;
  return [];
}

const FLAG = { Hindi:"🇮🇳", English:"🇬🇧", Tamil:"🇮🇳", Telugu:"🇮🇳", Malayalam:"🇮🇳",
  Urdu:"🇵🇰", Bengali:"🇧🇩", Arabic:"🇸🇦", Russian:"🇷🇺", Chinese:"🇨🇳", Japanese:"🇯🇵",
  Korean:"🇰🇷", French:"🇫🇷", Spanish:"🇪🇸", Portuguese:"🇵🇹", Filipino:"🇵🇭",
  Indonesian:"🇮🇩", Malaysian:"🇲🇾", Vietnamese:"🇻🇳", Nepali:"🇳🇵", Sinhala:"🇱🇰",
  Thai:"🇹🇭", Turkish:"🇹🇷", German:"🇩🇪", Italian:"🇮🇹", Dutch:"🇳🇱", Polish:"🇵🇱" };
function flag(tag) { return FLAG[tag] ?? ""; }

function languageTagPart(label, names) {
  const parts = names.length ? names : label.split(',').map(p=>p.trim()).filter(p=>p);
  if (parts.length >= 2 && !parts.some(p=>p==="Multi"||p==="Unknown"||p==="Original")) {
    return "(" + parts.map(p=>{
      const f = flag(p);
      return f ? f + " " + p : p;
    }).join(", ") + ")";
  }
  const single = parts.length === 1 ? parts[0] : null;
  const f = single ? flag(single) : "";
  return f ? "(" + f + " " + single + ")" : "(" + label + ")";
}

function stripQuality(text) {
  return orBlank(text).replace(RESOLUTION_TOKEN, " ").replace(QUALITY_ALIAS, " ")
    .replace(/\s+/g, " ").trim();
}

function displayName(serverName, audioLabel, subIndicator, sizeLabel, mediaLabel) {
  let rawName = orBlank(serverName).trim() || "Server";
  let cleanName = stripQuality(rawName) || "Server";
  let composed = subIndicator ? (cleanName + " " + orBlank(subIndicator).trim()).trim() : cleanName;
  let named = stripQuality(composed.replace(TRAILING_LANGUAGE, "")).trim() || "Server";
  named = stripQuality(named).trim() || "Server";
  const names = displayLanguageNamesFixed(audioLabel, mediaLabel);
  const tag = names.length === 1 ? names[0] : (names.length > 1 ? names.join(", ")
    : (languageLabel(audioLabel) || languageLabel(mediaLabel) || "Unknown"));
  const tagPart = languageTagPart(tag, names);
  const sizePart = orBlank(sizeLabel).trim();
  return [named, tagPart, sizePart].filter(x=>x).join(" ");
}

// ---- FIXED: priorityName (SourcePriority dialog key) ----
// Mirrors Kotlin LinkNaming.priorityName: base name + language tag only.
// All rendition tokens (source/audio/video/vision/depth), REMUX, and sizes are removed
// so every rendition of the same source+language shares one priority key.
const ALL_TOKENS_RE = [
  /\bWEB[ \-]?DL\b/gi, /\bWEB[ \-]?RIP\b/gi, /\bBLU[ \-]?RAY\b/gi,
  /\bBR[ \-]?RIP\b/gi, /\bHD[ \-]?RIP\b/gi, /\bDVD[ \-]?RIP\b/gi,
  /\bHDTV\b/gi, /\b(?:HD[ \-]?T[SCS]|HD[ \-]?CAM|CAM[ \-]?RIP|CAM|HDM)\b/gi,
  /\bDOLBY[ \-]?ATMOS\b|\bATMOS\b/gi,
  /\bTRUEHD\b/gi,
  /\b(?:DOLBY[ \-]*(?:DIGITAL)?[ \-]*|DDP?[ \-]*|E[ \-]?AC[ \-]?3[ \-]*|AC[ \-]?3)[5]\.1\b/gi,
  /\b(?:DOLBY[ \-]*(?:DIGITAL)?[ \-]*|DDP?[ \-]*|E[ \-]?AC[ \-]?3[ \-]*|AC[ \-]?3)[7]\.1\b/gi,
  /\b(?:DDP|DD[ \-]?\+|E[ \-]?AC[ \-]?3|AC[ \-]?3|DD)\b/gi,
  /\bDOLBY\b/gi, /\bDTS[ \-]?H[DI]\b/gi, /\bDTS\b/gi, /\bAAC\b/gi,
  /\bDOLBY[ \-]?VISION\b/gi, /\bDV\b/gi, /\bHDR10\+(?!\d)/gi, /\bHDR10\b/gi, /\bHDR\b/gi,
  /\bHEVC\b|\bH\.?[ \-]?265\b|\bX\.?265\b/gi,
  /\bH\.?[ \-]?264\b|\bX\.?264\b|\bAVC\b|\bMPEG[ \-]?4\b/gi,
  /\bAV1\b/gi, /\b10[ \-]?BIT\b/gi, /\b8[ \-]?BIT\b/gi,
];
const REMUX_RE = /\bREMUX\b/gi;
const SIZE_WORD_RE = /\b\d+(?:\.\d+)?\s?(?:GB|MB)\b/gi;

function stripRendition(text) {
  let out = text;
  for (const re of ALL_TOKENS_RE) out = out.replace(re, " ");
  out = out.replace(REMUX_RE, " ").replace(SIZE_WORD_RE, " ");
  out = out.replace(/\[\s*\]|\(\s*\)/g, " ");
  return out.replace(/\s+/g, " ").trim();
}

function priorityName(serverName, audioLabel, mediaLabel) {
  const rawName = orBlank(serverName).trim() || "Server";
  const cleanName = stripQuality(rawName) || "Server";
  const base = stripQuality(cleanName.replace(TRAILING_LANGUAGE, "")).trim() || "Server";
  const named = stripRendition(base) || base;
  const names = displayLanguageNamesFixed(audioLabel, mediaLabel);
  const tag = names.length === 1 ? names[0] : (names.length > 1 ? names.join(", ")
    : (languageLabel(audioLabel) || languageLabel(mediaLabel) || "Unknown"));
  const tagPart = languageTagPart(tag, names);
  return [named, tagPart].filter(x=>x).join(" ");
}

// ================= RUN TESTS =================
let pass = 0, fail = 0;
function check(name, actual, expected) {
  const ok = actual === expected;
  if (ok) pass++; else fail++;
  console.log((ok ? "PASS" : "FAIL") + " | " + name);
  if (!ok) {
    console.log("    expected: " + JSON.stringify(expected));
    console.log("    actual:   " + JSON.stringify(actual));
  }
}

console.log("\n=== FIX 1: LANGUAGE_SPLIT splits ; : tab ===");
check("fixed 'Hindi;English;Tamil' -> all three",
  languageNames("Hindi;English;Tamil").join(", "), "Hindi, English, Tamil");
check("fixed 'Hindi:English:Tamil' -> all three",
  languageNames("Hindi:English:Tamil").join(", "), "Hindi, English, Tamil");
check("fixed 'Hindi\tEnglish\tTamil' -> all three",
  languageNames("Hindi\tEnglish\tTamil").join(", "), "Hindi, English, Tamil");
check("OLD regex 'Hindi;English;Tamil' -> only Hindi (contrast)",
  languageNames("Hindi;English;Tamil", LANGUAGE_SPLIT_OLD).join(", "), "Hindi");

console.log("\n=== FIX 2: displayLanguageNames keeps media when richer ===");
check("fixed audio2/media5 -> all 5",
  displayLanguageNamesFixed("Hindi, English", "Hindi, English, Tamil, Telugu, Malayalam").join(", "),
  "Hindi, English, Tamil, Telugu, Malayalam");
check("fixed audio5/media2 -> audio 5",
  displayLanguageNamesFixed("Hindi, English, Tamil, Telugu, Malayalam", "Hindi, English").join(", "),
  "Hindi, English, Tamil, Telugu, Malayalam");
check("OLD audio2/media5 -> drops to 2 (contrast)",
  displayLanguageNamesOld("Hindi, English", "Hindi, English, Tamil, Telugu, Malayalam").join(", "),
  "Hindi, English");

console.log("\n=== FIX 3: streamAudioLabel drops only the subtitle clause, never the whole line ===");
const srcSub = { name: "HDHub 1080p Hindi", title: "", description: "Audio: Hindi, English, Tamil, Telugu, Malayalam | Subtitles: English", languages: "" };
check("fixed mixed line -> all 5 audio langs kept",
  streamAudioLabelFixed(srcSub), "Hindi, English, Tamil, Telugu, Malayalam");
check("OLD mixed line -> line dropped, name fallback guesses 'Hindi' (the reported bug)",
  streamAudioLabelOld(srcSub), "Hindi");
const srcSubMulti = { name: "1080p", title: "", description: "Audio: Hindi\nSubtitles: English", languages: "" };
check("fixed multi-line -> subtitle line dropped, audio line kept ('Hindi')",
  streamAudioLabelFixed(srcSubMulti), "Hindi");
check("OLD multi-line -> subtitle line dropped, audio line kept ('Hindi')",
  streamAudioLabelOld(srcSubMulti), "Hindi");

console.log("\n=== FIX 4: streamAudioLabel JSON array of objects handled ===");
const srcObj = { name: "HDHub 1080p Hindi", title: "", description: "", languages: '[{"name":"Hindi"},{"name":"English"},{"name":"Tamil"}]' };
check("fixed JSON array of objects -> all 3",
  streamAudioLabelFixed(srcObj), "Hindi, English, Tamil");

console.log("\n=== FIX 5: streamAudioLabel JSON array of strings still works ===");
const srcArr = { name: "Movie", title: "", description: "", languages: '["Hindi","English","Tamil"]' };
check("fixed JSON array of strings -> all 3",
  streamAudioLabelFixed(srcArr), "Hindi, English, Tamil");

console.log("\n=== FIX 6: streamAudioLabel no longer falls back to stream NAME ===");
const srcBlank = { name: "Hindi Movie 1080p", title: "", description: "", languages: "" };
check("fixed blank languages -> '' (not 'Hindi' from name)",
  streamAudioLabelFixed(srcBlank), "");
check("OLD blank languages -> 'Hindi' from name (contrast)",
  streamAudioLabelOld(srcBlank), "Hindi");

console.log("\n=== FIX 7: canonicalSubtitleName keeps full unknown string ===");
check("fixed 'Some Unknown Language' -> full string",
  canonicalSubtitleName("Some Unknown Language"), "Some Unknown Language");

console.log("\n=== FIX 8: hubLangOf stays a priority bucket; call site keeps full names ===");
// hubLangOf itself keeps its bucketing contract (used only for priority).
function hubLangOfBucket(languages) {
  const s = languages.toLowerCase();
  const other = /english|dual|multi|tamil|telugu|malayalam|kannada|bengali|marathi|punjabi|urdu|japanese|korean|chinese/;
  if (s.includes("hindi") && other.test(s)) return "Multi";
  if (s.includes("hindi")) return "Hindi";
  return languageLabel(languages) || "";
}
check("hubLangOf 'Hindi, English' -> 'Multi' (bucket kept)",
  hubLangOfBucket("Hindi, English"), "Multi");
check("hubLangOf 'Hindi' -> 'Hindi'",
  hubLangOfBucket("Hindi"), "Hindi");
check("hubLangOf 'Dual Audio' -> 'Multi'",
  hubLangOfBucket("Dual Audio"), "Multi");
check("call site label 'Hindi, English' -> full names kept",
  languageLabel("Hindi, English"), "Hindi, English");
// call-site priority mapping mirrors StreamEngine edit
function callSitePriority(languages) {
  return hubLangOfBucket(languages) === "Hindi" ? 4
    : hubLangOfBucket(languages) === "Multi" ? 2 : 1;
}
check("priority 'Hindi, English' -> 2 (Multi bucket)",
  callSitePriority("Hindi, English"), 2);
check("priority 'Hindi' -> 4",
  callSitePriority("Hindi"), 4);
check("priority 'English' -> 1",
  callSitePriority("English"), 1);

console.log("\n=== END-TO-END: displayName with 5 audio languages ===");
check("displayName 5 langs -> all 5 with flags",
  displayName("VidLink", "Hindi, English, Tamil, Telugu, Malayalam", null, null, null),
  "VidLink (🇮🇳 Hindi, 🇬🇧 English, 🇮🇳 Tamil, 🇮🇳 Telugu, 🇮🇳 Malayalam)");

console.log("\n=== FIX 9: priorityName drops all rendition tokens + sizes, keeps name+language ===");
check("priorityName full attrs -> name + language only",
  priorityName("MovieBox", "Hindi", "Film 2024 1080p WEB-DL HEVC 10bit Dolby 5.1"),
  "MovieBox (🇮🇳 Hindi)");
check("priorityName Remux -> dropped",
  priorityName("HubCloud Remux 1080p", "Hindi"),
  "HubCloud (🇮🇳 Hindi)");
check("priorityName size word -> dropped",
  priorityName("HubCloud 1.7GB", "Hindi"),
  "HubCloud (🇮🇳 Hindi)");
check("priorityName bracketed size -> dropped cleanly",
  priorityName("HubCloud [1.7GB]", "Hindi"),
  "HubCloud (🇮🇳 Hindi)");
check("priorityName WEB-DL HEVC in server name -> dropped",
  priorityName("BrandNewServer WEB-DL HEVC", "Tamil"),
  "BrandNewServer (🇮🇳 Tamil)");
check("priorityName same name+lang distinct from other lang",
  priorityName("VidLink", "English"), "VidLink (🇬🇧 English)");
check("priorityName quality variants collapse to one key",
  priorityName("FSL 1080p", "Hindi"), priorityName("FSL 720p", "Hindi"));
check("priorityName unknown language stays labeled",
  priorityName("PrimeSrc", ""), "PrimeSrc (Unknown)");
check("priorityName multi language",
  priorityName("VidLink", "Hindi+English"), "VidLink (🇮🇳 Hindi, 🇬🇧 English)");

console.log("\n=== SUMMARY ===");
console.log("PASS: " + pass + "  FAIL: " + fail);
process.exit(fail > 0 ? 1 : 0);
