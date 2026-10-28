// A small stand-in for the design tool's runtime, so the `.dc.html` mockups under docs/design/ can be drawn
// headless (tools/design-render/render.mjs). It understands what the mockups use: <x-dc>, <helmet>, {{path}}
// bindings in text and attributes, <sc-if value>, <sc-for list as>, <dc-import name ...props>, onClick/onChange
// handlers, and a `class Component extends DCLogic` with state, setState and renderVals().
(function () {
  class DCLogic {
    constructor(props) { this.props = props || {}; this.state = {}; }
    setState(patch) { Object.assign(this.state, typeof patch === 'function' ? patch(this.state) : patch); if (this.__rerender) this.__rerender(); }
    renderVals() { return {}; }
  }
  window.DCLogic = DCLogic;

  const BIND = /\{\{\s*([^}]+?)\s*\}\}/g;
  function lookup(scope, expr) {
    try { return Function('s', 'with (s) { return (' + expr + '); }')(scope); } catch (e) { return undefined; }
  }
  function interp(str, scope) {
    const whole = str.match(/^\{\{\s*([^}]+?)\s*\}\}$/);
    if (whole) return lookup(scope, whole[1]);
    return str.replace(BIND, (_, e) => { const v = lookup(scope, e); return v == null ? '' : String(v); });
  }

  async function loadDoc(url) {
    const text = await (await fetch(url)).text();
    const doc = new DOMParser().parseFromString(text, 'text/html');
    const xdc = doc.querySelector('x-dc');
    const helmet = xdc.querySelector('helmet');
    const script = doc.querySelector('script[data-dc-script]');
    let Comp = DCLogic;
    if (script) Comp = Function('DCLogic', script.textContent + '\n;return Component;')(DCLogic);
    let defaults = {};
    try {
      const p = JSON.parse(script.getAttribute('data-props') || '{}');
      for (const k in p) if (p[k] && 'default' in p[k]) defaults[k] = p[k].default;
    } catch (e) {}
    if (helmet) helmet.remove();
    return { nodes: Array.from(xdc.childNodes), helmet, Comp, defaults, base: url };
  }

  async function renderNodes(nodes, scope, parent, ctx) {
    for (const n of nodes) await renderNode(n, scope, parent, ctx);
  }

  async function renderNode(n, scope, parent, ctx) {
    if (n.nodeType === 3) { parent.appendChild(document.createTextNode(interp(n.textContent, scope))); return; }
    if (n.nodeType !== 1) return;
    const tag = n.tagName.toLowerCase();
    if (tag === 'sc-if') { if (interp(n.getAttribute('value') || '', scope)) await renderNodes(n.childNodes, scope, parent, ctx); return; }
    if (tag === 'sc-for') {
      const list = interp(n.getAttribute('list') || '', scope) || [];
      const as = n.getAttribute('as') || 'item';
      let i = 0;
      for (const item of list) { const s = Object.create(scope); s[as] = item; s.$index = i++; await renderNodes(n.childNodes, s, parent, ctx); }
      return;
    }
    if (tag === 'dc-import') {
      const props = {};
      for (const a of n.attributes) if (a.name !== 'name' && !a.name.startsWith('hint-')) props[a.name] = interp(a.value, scope);
      const holder = document.createElement('div');
      holder.style.display = 'contents';
      parent.appendChild(holder);
      await mount(new URL(n.getAttribute('name') + '.dc.html', ctx.base).href, holder, props);
      return;
    }
    const ns = n.namespaceURI;
    const el = ns && ns !== 'http://www.w3.org/1999/xhtml' ? document.createElementNS(ns, n.tagName) : document.createElement(tag);
    for (const a of n.attributes) {
      if (a.name.startsWith('hint-')) continue;
      if (/^on[a-z]+$/i.test(a.name)) {
        const fn = interp(a.value, scope);
        if (typeof fn === 'function') el.addEventListener(a.name.slice(2).toLowerCase(), (ev) => fn(ev));
        continue;
      }
      const v = interp(a.value, scope);
      if (v === false || v == null) continue;
      if (a.name === 'value' && (tag === 'input' || tag === 'textarea')) el.value = v;
      el.setAttribute(a.name, v === true ? '' : String(v));
    }
    parent.appendChild(el);
    await renderNodes(n.childNodes, scope, tag === 'template' ? el.content : el, ctx);
  }

  async function mount(url, holder, props) {
    const d = await loadDoc(url);
    if (d.helmet) for (const c of Array.from(d.helmet.childNodes)) document.head.appendChild(document.importNode(c, true));
    const inst = new d.Comp(Object.assign({}, d.defaults, props));
    const draw = async () => {
      const vals = inst.renderVals() || {};
      const scope = Object.assign(Object.create(null), inst.props, vals);
      const frag = document.createElement('div');
      frag.style.display = 'contents';
      await renderNodes(d.nodes, scope, frag, { base: d.base });
      holder.replaceChildren(frag);
    };
    inst.__rerender = () => { draw(); };
    await draw();
  }
  window.__dcMount = mount;

  document.addEventListener('DOMContentLoaded', async () => {
    const xdc = document.querySelector('x-dc');
    if (!xdc) return;
    xdc.remove();
    const holder = document.createElement('div');
    document.body.appendChild(holder);
    try { await mount(location.href, holder, {}); } catch (e) { console.error(e); }
    window.__dcReady = true;
  });
})();
