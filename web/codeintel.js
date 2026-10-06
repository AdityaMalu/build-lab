/*
 * Lightweight code intelligence for the editor: no language server, just per-language patterns.
 *
 *   CodeIntel.index(files, lang)  -> [{name, kind, file, line, ch}]   declarations found in the files
 *   CodeIntel.definitions(symbols, name, from)  -> the same, best match first
 *   CodeIntel.completions(symbols, files, lang, prefix, afterDot) -> [{text, kind}]
 *
 * "files" maps a path to its text. Lines and columns are 0-based (CodeMirror's convention).
 * It reads declarations line by line, so it can be fooled by unusual formatting; it never runs code.
 */
(function () {
  "use strict";

  const KEYWORDS = {
    java: "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long native new package private protected public record return short static super switch synchronized this throw throws transient try var void volatile while null true false",
    python: "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield None True False self",
    go: "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var nil true false",
    cpp: "auto bool break case catch char class const constexpr continue default delete do double else enum explicit false float for friend if inline int long mutable namespace new noexcept nullptr operator override private protected public return short signed sizeof static struct switch template this throw true try typedef typename unsigned using virtual void volatile while",
  };

  // common standard-library names, so completion helps before you've typed them once
  const BUILTINS = {
    java: "String StringBuilder Integer Long Double Boolean Character Math System Object Objects Optional List ArrayList LinkedList Map HashMap LinkedHashMap TreeMap Set HashSet LinkedHashSet TreeSet Deque ArrayDeque Queue PriorityQueue Collections Arrays Iterator Comparator Collectors Stream IntStream Instant Duration LocalDateTime ZoneId UUID ConcurrentHashMap ConcurrentLinkedQueue AtomicInteger AtomicLong AtomicReference ReentrantLock ReentrantReadWriteLock Condition Semaphore CountDownLatch ExecutorService Executors TimeUnit CompletableFuture Future Callable Runnable Thread IllegalArgumentException IllegalStateException NullPointerException UnsupportedOperationException RuntimeException Exception Override FunctionalInterface",
    python: "print len range enumerate zip sorted reversed min max sum abs any all map filter isinstance issubclass hasattr getattr setattr str int float bool bytes list dict set tuple frozenset object type super property staticmethod classmethod dataclass field Enum ValueError KeyError TypeError IndexError RuntimeError PermissionError LookupError Exception NotImplementedError threading Lock RLock Condition Thread time monotonic collections defaultdict deque OrderedDict Counter heapq heappush heappop bisect math hashlib json re itertools functools Optional List Dict Set Tuple Callable Iterable",
    go: "make len cap append copy delete new panic recover close error string int int64 int32 uint32 uint64 float64 bool byte rune any errors New Is As Errorf fmt Sprintf Println strings Builder Split Join Fields ToLower TrimSpace HasPrefix strconv Itoa Atoi sort Slice Ints Strings sync Mutex RWMutex WaitGroup Once Lock Unlock RLock RUnlock atomic time Duration Now Since Millisecond Second container heap list",
    cpp: "std string vector map unordered_map set unordered_set deque queue priority_queue stack pair tuple optional variant array list mutex shared_mutex lock_guard unique_lock shared_lock scoped_lock condition_variable thread atomic chrono size_t int64_t uint64_t uint32_t move forward make_shared make_unique shared_ptr unique_ptr function sort find begin end size empty push_back emplace_back insert erase count at front back to_string stoi stoll invalid_argument out_of_range runtime_error logic_error exception what",
  };

  const NOT_NAMES = new Set(("if for while switch catch return new else throw sizeof do case try synchronized " +
    "elif except with assert yield await defer go select delete").split(" "));

  function add(out, name, kind, file, line, ch) {
    if (!name || NOT_NAMES.has(name)) return;
    out.push({ name, kind, file, line, ch: Math.max(0, ch) });
  }

  // column of the name in the line (the regex gives us the name, not where it is)
  function col(text, name, from) {
    const re = new RegExp("\\b" + name + "\\b", "g");
    re.lastIndex = from || 0;
    const m = re.exec(text);
    return m ? m.index : 0;
  }

  const isComment = (t, lang) => (lang === "python" ? /^\s*#/ : /^\s*(\/\/|\*|\/\*)/).test(t);

  function indexJava(file, lines, out) {
    const typeRe = /\b(class|interface|enum|record|@interface)\s+([A-Za-z_]\w*)/;
    const methodRe = /^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|protected|static|final|abstract|synchronized|default|native|strictfp)\s+)*(?:<[^>]+>\s+)?[\w$.]+(?:<[^()]*>)?(?:\[\])*\s+([A-Za-z_$][\w$]*)\s*\(/;
    const ctorRe = /^\s*(?:public|private|protected)\s+([A-Z][\w$]*)\s*\(/;
    const fieldRe = /^\s*(?:(?:public|private|protected|static|final|volatile|transient)\s+)*(?:[\w$.]+(?:<[^;=()]*>)?(?:\[\])*)\s+([A-Za-z_$][\w$]*)\s*(?:=(?!=)|;)/;
    const forRe = /\bfor\s*\(\s*(?:final\s+)?[\w$.<>\[\], ]+?\s+([A-Za-z_$][\w$]*)\s*[:=]/;
    const catchRe = /\bcatch\s*\(\s*[\w$.| ]+\s+([A-Za-z_$][\w$]*)\s*\)/;
    lines.forEach((t, i) => {
      if (isComment(t, "java")) return;
      let m;
      const type = typeRe.exec(t);
      if (type) add(out, type[2], type[1] === "interface" ? "interface" : "class", file, i, col(t, type[2]));
      if (type && /\brecord\b/.test(type[1])) {
        // record components are its fields: record Comment(long id, String text)
        const open = t.indexOf("(");
        t.slice(open + 1, t.indexOf(")", open)).split(",").forEach((p) => {
          const pm = /([A-Za-z_$][\w$]*)\s*$/.exec(p.trim());
          if (pm) add(out, pm[1], "field", file, i, col(t, pm[1], open));
        });
        return;
      }
      if (type) return;
      if ((m = ctorRe.exec(t))) add(out, m[1], "constructor", file, i, col(t, m[1]));
      else if ((m = methodRe.exec(t)) && !/^\s*(return|new|throw|else)\b/.test(t)) add(out, m[1], "method", file, i, col(t, m[1]));
      else if ((m = fieldRe.exec(t)) && !/^\s*(return|throw|package|import)\b/.test(t)) {
        add(out, m[1], /^\s{0,4}(private|public|protected|static|final)/.test(t) ? "field" : "var", file, i, col(t, m[1]));
      }
      if ((m = forRe.exec(t))) add(out, m[1], "var", file, i, col(t, m[1]));
      if ((m = catchRe.exec(t))) add(out, m[1], "var", file, i, col(t, m[1]));
      // parameters of a method or constructor declared on this line
      const decl = ctorRe.exec(t) || methodRe.exec(t);
      if (decl && !/^\s*(return|new|throw|else)\b/.test(t)) {
        const open = t.indexOf("(", t.indexOf(decl[1]));
        const close = t.indexOf(")", open);
        const params = t.slice(open + 1, close < 0 ? undefined : close);
        params.split(",").forEach((p) => {
          const pm = /([A-Za-z_$][\w$]*)\s*$/.exec(p.replace(/\.\.\./, " ").trim());
          if (pm && /\s/.test(p.trim())) add(out, pm[1], "param", file, i, col(t, pm[1], open));
        });
      }
    });
  }

  function indexPython(file, lines, out) {
    lines.forEach((t, i) => {
      if (isComment(t, "python")) return;
      let m;
      if ((m = /^\s*class\s+([A-Za-z_]\w*)/.exec(t))) add(out, m[1], "class", file, i, col(t, m[1]));
      if ((m = /^(\s*)(?:async\s+)?def\s+([A-Za-z_]\w*)\s*\(([^)]*)/.exec(t))) {
        add(out, m[2], m[1].length ? "method" : "function", file, i, col(t, m[2]));
        const open = t.indexOf("(");
        m[3].split(",").forEach((p) => {
          const name = p.split(/[:=]/)[0].replace(/^\*+/, "").trim();
          if (/^[A-Za-z_]\w*$/.test(name) && name !== "self" && name !== "cls") add(out, name, "param", file, i, col(t, name, open));
        });
      }
      const self = /\bself\.([A-Za-z_]\w*)\s*(?::[^=]+)?=(?!=)/g;
      while ((m = self.exec(t))) add(out, m[1], "field", file, i, m.index + 5);
      if ((m = /^(\s*)([A-Za-z_]\w*)\s*(?::\s*[^=]+)?=(?!=)/.exec(t))) add(out, m[2], m[1].length ? "var" : "const", file, i, m[1].length);
      if ((m = /^\s*for\s+([A-Za-z_]\w*)(?:\s*,\s*([A-Za-z_]\w*))?\s+in\b/.exec(t))) {
        add(out, m[1], "var", file, i, col(t, m[1]));
        if (m[2]) add(out, m[2], "var", file, i, col(t, m[2]));
      }
      if ((m = /\b(?:as)\s+([A-Za-z_]\w*)\s*:/.exec(t))) add(out, m[1], "var", file, i, col(t, m[1]));
      if ((m = /^\s*(?:from\s+[\w.]+\s+)?import\s+(.+)$/.exec(t))) {
        m[1].split(",").forEach((part) => {
          const name = part.trim().split(/\s+as\s+/).pop().replace(/[()]/g, "").trim();
          if (/^[A-Za-z_]\w*$/.test(name)) add(out, name, "import", file, i, col(t, name));
        });
      }
    });
  }

  function indexGo(file, lines, out) {
    let block = null; // "struct" | "interface" | "const" | "var" while inside one
    lines.forEach((t, i) => {
      if (isComment(t, "go")) return;
      let m;
      if (block) {
        if (/^\s*[)}]/.test(t)) { block = null; return; }
        if ((m = /^\s+([A-Za-z_]\w*)\b/.exec(t))) {
          const kind = { struct: "field", interface: "method", const: "const", var: "var" }[block];
          add(out, m[1], kind, file, i, col(t, m[1]));
        }
        return;
      }
      if ((m = /^type\s+([A-Za-z_]\w*)\s+(struct|interface)?/.exec(t))) {
        add(out, m[1], m[2] === "interface" ? "interface" : "type", file, i, col(t, m[1]));
        if (m[2] && /\{\s*$/.test(t)) block = m[2];
      }
      if ((m = /^(const|var)\s*\(\s*$/.exec(t))) block = m[1];
      else if ((m = /^(const|var)\s+([A-Za-z_]\w*)/.exec(t))) add(out, m[2], m[1], file, i, col(t, m[2]));
      if ((m = /^func\s+(?:\(\s*(\w+)\s+\*?[\w.]+\s*\)\s*)?([A-Za-z_]\w*)\s*\(([^)]*)/.exec(t))) {
        add(out, m[2], m[1] ? "method" : "function", file, i, col(t, m[2], 5));
        if (m[1]) add(out, m[1], "param", file, i, col(t, m[1]));
        const open = t.indexOf("(", t.indexOf(m[2]));
        m[3].split(",").forEach((p) => {
          const name = p.trim().split(/\s+/)[0];
          if (/^[A-Za-z_]\w*$/.test(name)) add(out, name, "param", file, i, col(t, name, open));
        });
      }
      const short = /(?:^|[\s(;])((?:[A-Za-z_]\w*\s*,\s*)*[A-Za-z_]\w*)\s*:=/g;
      while ((m = short.exec(t))) {
        m[1].split(",").forEach((n) => {
          n = n.trim();
          if (n !== "_") add(out, n, "var", file, i, col(t, n, m.index));
        });
      }
      if ((m = /^\s+var\s+([A-Za-z_]\w*)/.exec(t))) add(out, m[1], "var", file, i, col(t, m[1]));
    });
  }

  function indexCpp(file, lines, out) {
    const typeRe = /\b(class|struct|enum\s+class|enum|union|namespace)\s+([A-Za-z_]\w*)\s*(?:final\s*)?[:{;]?/;
    const usingRe = /^\s*using\s+([A-Za-z_]\w*)\s*=/;
    const funcRe = /^\s*(?:template\s*<[^>]*>\s*)?(?:(?:static|inline|virtual|explicit|constexpr|friend|extern|const|unsigned|signed)\s+)*(?:[\w:]+(?:<[^;()]*>)?[\s*&]+)+(?:[\w]+::)*(~?[A-Za-z_]\w*)\s*\(/;
    const ctorRe = /^\s*(?:explicit\s+)?([A-Z]\w*)\s*\([^;]*\)\s*(?::|\{|$)/;
    const varRe = /^\s*(?:(?:static|const|constexpr|mutable|inline|thread_local)\s+)*(?:[\w:]+(?:<[^;()]*>)?[\s*&]+)+([A-Za-z_]\w*)\s*(?:\[[^\]]*\])?\s*(?:\{[^;]*\}|=[^=][^;]*|\([^;]*\))?\s*;/;
    const forRe = /\bfor\s*\(\s*(?:const\s+)?[\w:<>,*& ]+?[\s*&]+([A-Za-z_]\w*)\s*(?::|=)/;
    lines.forEach((t, i) => {
      if (isComment(t, "cpp") || /^\s*#/.test(t)) return;
      let m;
      const forward = /^\s*(class|struct)\s+\w+\s*;\s*$/.test(t); // "class Foo;" only names a type
      if ((m = typeRe.exec(t)) && !forward) add(out, m[2], m[1] === "namespace" ? "namespace" : "class", file, i, col(t, m[2]));
      if ((m = usingRe.exec(t))) add(out, m[1], "type", file, i, col(t, m[1]));
      const bad = /^\s*(return|else|new|delete|throw|case|goto|co_return|using)\b/.test(t);
      let declared = null;
      if (!bad && (m = ctorRe.exec(t))) declared = m[1];
      else if (!bad && (m = funcRe.exec(t)) && !/\b(if|for|while|switch|catch|sizeof)\s*\(/.test(t.slice(0, t.indexOf(m[1])))) declared = m[1];
      if (declared) {
        add(out, declared, "function", file, i, col(t, declared));
        const open = t.indexOf("(", t.indexOf(declared));
        const close = t.indexOf(")", open);
        t.slice(open + 1, close < 0 ? undefined : close).split(",").forEach((p) => {
          const pm = /([A-Za-z_]\w*)\s*(?:=[^,]*)?$/.exec(p.trim());
          if (pm && /[\s*&]/.test(p.trim().replace(/\s*=.*$/, ""))) add(out, pm[1], "param", file, i, col(t, pm[1], open));
        });
      } else if (!bad && (m = varRe.exec(t))) {
        add(out, m[1], /^\s{0,4}\S/.test(t) || /_$/.test(m[1]) ? "field" : "var", file, i, col(t, m[1]));
      }
      if ((m = forRe.exec(t))) add(out, m[1], "var", file, i, col(t, m[1]));
    });
  }

  const INDEXERS = { java: indexJava, python: indexPython, go: indexGo, cpp: indexCpp };

  function index(files, lang) {
    const out = [];
    const indexer = INDEXERS[lang] || indexJava;
    for (const [file, text] of Object.entries(files)) indexer(file, (text || "").split("\n"), out);
    return out;
  }

  // Ranking: a declaration in the same file just above the use (locals, params) beats everything;
  // then types and methods over plain variables; then the current file; then other files.
  const KIND_RANK = { class: 0, interface: 0, type: 0, namespace: 0, constructor: 1, method: 1, function: 1,
    field: 2, const: 2, import: 4, var: 3, param: 3 };

  // from.secondary: file prefix (the tests) that ranks after your own code, so a call like
  // s.addComment(...) in a test opens your addComment, not a test method that shares the name
  function definitions(symbols, name, from) {
    const hits = symbols.filter((s) => s.name === name);
    const local = (s) => s.file === from.file && s.line <= from.line && (s.kind === "var" || s.kind === "param");
    const secondary = (s) => !!from.secondary && s.file.startsWith(from.secondary);
    const score = (s) => {
      if (local(s)) return from.line - s.line; // nearest one above wins
      return 100000 + (secondary(s) ? 50000 : 0) + (KIND_RANK[s.kind] ?? 5) * 1000
        + (s.file === from.file ? 0 : 500) + Math.min(s.line, 499);
    };
    return hits.sort((a, b) => score(a) - score(b));
  }

  function completions(symbols, files, lang, prefix, afterDot) {
    const seen = new Map(); // text -> kind (first kind wins: declared symbols before plain words)
    const lower = prefix.toLowerCase();
    const offer = (text, kind) => {
      if (text === prefix || seen.has(text)) return;
      if (!text.toLowerCase().startsWith(lower)) return;
      seen.set(text, kind);
    };
    const members = new Set(["method", "field", "function", "const"]);
    const ordered = afterDot
      ? [...symbols.filter((s) => members.has(s.kind)), ...symbols.filter((s) => !members.has(s.kind))]
      : symbols.filter((s) => s.kind !== "param" && s.kind !== "var").concat(symbols.filter((s) => s.kind === "param" || s.kind === "var"));
    ordered.forEach((s) => offer(s.name, s.kind));
    if (!afterDot) (KEYWORDS[lang] || "").split(" ").forEach((k) => offer(k, "keyword"));
    (BUILTINS[lang] || "").split(" ").forEach((b) => offer(b, "library"));
    // any other identifier already used in the open files or the tests
    for (const text of Object.values(files)) {
      const words = (text || "").match(/[A-Za-z_]\w{2,}/g) || [];
      words.forEach((w) => offer(w, "word"));
    }
    const rank = { keyword: 3, library: 2, word: 4 };
    return [...seen.entries()]
      .map(([text, kind]) => ({ text, kind }))
      .sort((a, b) => {
        const ca = a.text.startsWith(prefix) ? 0 : 1, cb = b.text.startsWith(prefix) ? 0 : 1;
        if (ca !== cb) return ca - cb;
        const ra = rank[a.kind] ?? 0, rb = rank[b.kind] ?? 0;
        if (ra !== rb) return ra - rb;
        return a.text.length - b.text.length || a.text.localeCompare(b.text);
      })
      .slice(0, 60);
  }

  window.CodeIntel = { index, definitions, completions };
})();
