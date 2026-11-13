window.PhotoDB = (() => {
  let dbp;
  const open = () => dbp || (dbp = new Promise((res, rej) => {
    const r = indexedDB.open('nomad', 1);
    r.onupgradeneeded = () => {
      const s = r.result.createObjectStore('photos', { keyPath: 'id' });
      s.createIndex('country', 'country');
    };
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  }));
  const tx = async (mode, fn) => {
    const db = await open();
    return new Promise((res, rej) => {
      const t = db.transaction('photos', mode);
      const out = fn(t.objectStore('photos'));
      t.oncomplete = () => res(out && 'result' in out ? out.result : undefined);
      t.onerror = () => rej(t.error);
      t.onabort = () => rej(t.error);
    });
  };
  return {
    add: p => tx('readwrite', s => s.put(p)),
    remove: id => tx('readwrite', s => s.delete(id)),
    byCountry: c => tx('readonly', s => s.index('country').getAll(c))
      .then(l => l.sort((a, b) => a.date - b.date)),
    counts: async () => {
      const db = await open();
      return new Promise((res, rej) => {
        const out = {};
        const c = db.transaction('photos').objectStore('photos').index('country').openKeyCursor();
        c.onsuccess = () => {
          const cur = c.result;
          if (!cur) return res(out);
          out[cur.key] = (out[cur.key] || 0) + 1;
          cur.continue();
        };
        c.onerror = () => rej(c.error);
      });
    }
  };
})();
