// The right-hand panel: sample picker, Monaco editor with the layout schema, and the issue list.
// Rendering and validation happen in Kotlin (window.owntv); this file only moves text and results.
import { findNodeAtLocation, parseTree } from 'https://cdn.jsdelivr.net/npm/jsonc-parser@3.3.1/+esm';

const MONACO = 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.2/min';
const RENDER_DELAY_MS = 250;

const $ = (id) => document.getElementById(id);
const sampleSelect = $('sample');
const pageSelect = $('page');
const status = $('status');
const issueList = $('issues');

/** Original text of each sample, and the user's edits, by file. */
const originals = new Map();
const edits = new Map();
let current = null;
let editor = null;
let monaco = null;
let lastIssues = [];

function store(key, value) {
  try { value == null ? localStorage.removeItem(key) : localStorage.setItem(key, value); } catch { /* storage may be blocked */ }
}

function stored(key) {
  try { return localStorage.getItem(key); } catch { return null; }
}

/** Resolves once the Kotlin side has installed window.owntv. */
const rendererReady = new Promise((resolve) => {
  if (window.owntv) resolve(window.owntv);
  else window.addEventListener('owntv-ready', () => resolve(window.owntv), { once: true });
});

function loadMonaco() {
  // Workers come from the CDN, so start them through a same-origin blob.
  window.MonacoEnvironment = {
    getWorkerUrl: () => URL.createObjectURL(new Blob(
      [`self.MonacoEnvironment = { baseUrl: '${MONACO}/' }; importScripts('${MONACO}/vs/base/worker/workerMain.js');`],
      { type: 'text/javascript' },
    )),
  };
  return new Promise((resolve, reject) => {
    window.require.config({ paths: { vs: `${MONACO}/vs` } });
    window.require(['vs/editor/editor.main'], () => resolve(window.monaco), reject);
  });
}

async function fetchText(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error(`${response.status} ${path}`);
  return response.text();
}

function fillSamples(index) {
  for (const [group, label] of [['examples', 'Examples'], ['looks', 'Looks']]) {
    const optgroup = document.createElement('optgroup');
    optgroup.label = label;
    for (const item of index[group] ?? []) {
      const option = new Option(item.name, item.file);
      optgroup.append(option);
    }
    sampleSelect.append(optgroup);
  }
}

async function openSample(file) {
  if (current && editor) edits.set(current, editor.getValue());
  current = file;
  sampleSelect.value = file;
  history.replaceState(null, '', `#${file}`);
  if (!originals.has(file)) originals.set(file, await fetchText(`layouts/${file}`));
  editor.setValue(edits.get(file) ?? originals.get(file));
  editor.setScrollTop(0);
}

/** `$.pages[0].root.children[2]` as jsonc-parser path segments. */
function pathSegments(path) {
  const segments = [];
  for (const match of path.replace(/^\$/, '').matchAll(/\.([^.[\]]+)|\[(\d+)\]/g)) {
    segments.push(match[1] !== undefined ? match[1] : Number(match[2]));
  }
  return segments;
}

/** The text range of [path], or of its closest existing parent. */
function rangeOf(path) {
  const model = editor.getModel();
  const tree = parseTree(model.getValue());
  if (!tree) return null;
  const segments = pathSegments(path);
  for (let n = segments.length; n >= 0; n--) {
    const node = n === 0 ? tree : findNodeAtLocation(tree, segments.slice(0, n));
    if (!node) continue;
    // For a property, underline its key and value; for the root, only the first line.
    const target = node.parent?.type === 'property' ? node.parent : node;
    const start = model.getPositionAt(target.offset);
    const end = n === 0 ? model.getPositionAt(target.offset + 1) : model.getPositionAt(target.offset + target.length);
    return new monaco.Range(start.lineNumber, start.column, end.lineNumber, end.column);
  }
  return null;
}

function showIssues(result) {
  lastIssues = result.issues;
  const errors = result.issues.filter((i) => i.severity === 'error').length;
  const warnings = result.issues.filter((i) => i.severity === 'warning').length;
  if (result.ok) {
    status.textContent = errors + warnings === 0 ? 'Valid layout' : `Valid, ${warnings} warning${warnings === 1 ? '' : 's'}`;
  } else {
    status.textContent = `${errors} error${errors === 1 ? '' : 's'}${result.stale ? ', showing the last valid layout' : ''}`;
  }
  status.dataset.state = result.ok ? (warnings ? 'warning' : 'ok') : 'error';

  issueList.replaceChildren(...result.issues.map((issue, i) => {
    const item = document.createElement('li');
    item.dataset.severity = issue.severity;
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.index = i;
    const where = document.createElement('code');
    where.textContent = issue.path;
    const message = document.createElement('span');
    message.textContent = issue.message;
    button.append(where, message);
    item.append(button);
    return item;
  }));

  const severities = { error: monaco.MarkerSeverity.Error, warning: monaco.MarkerSeverity.Warning, info: monaco.MarkerSeverity.Info };
  const markers = result.issues.map((issue) => {
    const range = rangeOf(issue.path) ?? new monaco.Range(1, 1, 1, 2);
    return { ...range, severity: severities[issue.severity], message: issue.message, source: 'OwnTV' };
  });
  monaco.editor.setModelMarkers(editor.getModel(), 'owntv', markers);

  pageSelect.replaceChildren(...result.pages.map((page) => new Option(page.title, page.id)));
  pageSelect.value = result.page ?? '';
  $('page-field').hidden = result.pages.length < 2;
}

function download() {
  const blob = new Blob([editor.getValue()], { type: 'application/json' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = current?.split('/').pop() ?? 'layout.json';
  link.click();
  URL.revokeObjectURL(link.href);
}

async function start() {
  const [index, schemaText] = await Promise.all([fetchText('layouts/index.json'), fetchText('schema.json')]);
  fillSamples(JSON.parse(index));
  const schema = JSON.parse(schemaText);

  monaco = await loadMonaco();
  monaco.languages.json.jsonDefaults.setDiagnosticsOptions({
    validate: true,
    allowComments: true,
    trailingCommas: 'ignore',
    schemas: [{ uri: schema.$id ?? 'owntv-layout.schema.json', fileMatch: ['*'], schema }],
  });
  const model = monaco.editor.createModel('', 'json', monaco.Uri.parse('inmemory://owntv/layout.json'));
  editor = monaco.editor.create($('editor'), {
    model,
    theme: 'vs-dark',
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 13,
    tabSize: 2,
    scrollBeyondLastLine: false,
    fixedOverflowWidgets: true,
  });

  const owntv = await rendererReady;
  window.addEventListener('owntv-result', (event) => showIssues(event.detail));

  let timer = 0;
  model.onDidChangeContent(() => {
    clearTimeout(timer);
    timer = setTimeout(() => owntv.render(editor.getValue()), RENDER_DELAY_MS);
  });

  sampleSelect.addEventListener('change', () => openSample(sampleSelect.value));
  pageSelect.addEventListener('change', () => owntv.setPage(pageSelect.value));
  issueList.addEventListener('click', (event) => {
    const button = event.target.closest('button[data-index]');
    const range = button && rangeOf(lastIssues[Number(button.dataset.index)].path);
    if (!range) return;
    editor.setSelection(range);
    editor.revealRangeInCenter(range);
    editor.focus();
  });
  $('reset').addEventListener('click', () => {
    edits.delete(current);
    editor.setValue(originals.get(current));
  });
  $('format').addEventListener('click', () => editor.getAction('editor.action.formatDocument').run());
  $('copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(editor.getValue());
      $('copy').textContent = 'Copied';
    } catch {
      $('copy').textContent = 'Copy failed';
    }
    setTimeout(() => { $('copy').textContent = 'Copy'; }, 1500);
  });
  $('download').addEventListener('click', download);

  const metrics = $('metrics');
  metrics.checked = stored('owntv.metrics') === '1';
  owntv.setMetrics(metrics.checked);
  metrics.addEventListener('change', () => {
    owntv.setMetrics(metrics.checked);
    store('owntv.metrics', metrics.checked ? '1' : null);
  });

  // The URL hash names the open sample, so links like #looks/roku.json work.
  const files = [...sampleSelect.options].map((o) => o.value);
  const fromHash = () => {
    const wanted = decodeURIComponent(location.hash.slice(1));
    return files.includes(wanted) ? wanted : null;
  };
  window.addEventListener('hashchange', () => {
    const file = fromHash();
    if (file && file !== current) openSample(file);
  });
  await openSample(fromHash() ?? (files.includes('examples/default.json') ? 'examples/default.json' : files[0]));
}

start().catch((error) => {
  status.textContent = `Could not start: ${error.message}`;
  status.dataset.state = 'error';
  console.error(error);
});
