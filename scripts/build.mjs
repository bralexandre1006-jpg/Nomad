import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const countries = require('i18n-iso-countries');
countries.registerLocale(require('i18n-iso-countries/langs/fr.json'));

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const www = path.join(root, 'www');
const nm = p => path.join(root, 'node_modules', p);

const SOVEREIGN = `AF AL DZ AD AO AG AR AM AU AT AZ BS BH BD BB BY BE BZ BJ BT BO BA BW BR BN BG BF BI CV KH CM CA CF TD CL CN CO KM CG CD CR CI HR CU CY CZ DK DJ DM DO EC EG SV GQ ER EE SZ ET FJ FI FR GA GM GE DE GH GR GD GT GN GW GY HT HN HU IS IN ID IR IQ IE IT JM JP JO KZ KE KI KP KR KW KG LA LV LB LS LR LY LI LT LU MG MW MY MV ML MT MH MR MU MX FM MD MC MN ME MA MZ MM NA NR NP NL NZ NI NE NG MK NO OM PK PW PA PG PY PE PH PL PT QA RO RU RW KN LC VC WS SM ST SA SN RS SC SL SG SK SI SB SO ZA SS ES LK SD SR SE CH SY TJ TZ TH TL TG TO TT TN TR TM TV UG UA AE GB US UY UZ VU VE VN YE ZM ZW VA PS`.split(/\s+/);
if (new Set(SOVEREIGN).size !== 194) throw new Error('Liste des pays invalide: ' + new Set(SOVEREIGN).size);

const NAME_FIX = { US: 'États-Unis', GB: 'Royaume-Uni', KP: 'Corée du Nord', KR: 'Corée du Sud', CD: 'RD Congo', CG: 'Congo', VA: 'Vatican', PS: 'Palestine', RU: 'Russie', IR: 'Iran', SY: 'Syrie', MD: 'Moldavie', LA: 'Laos', TZ: 'Tanzanie', BO: 'Bolivie', VE: 'Venezuela', FM: 'Micronésie', MK: 'Macédoine du Nord', CZ: 'Tchéquie', VN: 'Viêt Nam', BN: 'Brunei', SZ: 'Eswatini', CV: 'Cap-Vert', TL: 'Timor oriental', CI: "Côte d'Ivoire", MM: 'Myanmar', DO: 'Rép. dominicaine', CF: 'Centrafrique', AE: 'Émirats arabes unis' };

const CONTINENTS = {
  AF: ['Afrique', 'DZ AO BJ BW BF BI CV CM CF TD KM CG CD CI DJ EG GQ ER SZ ET GA GM GH GN GW KE LS LR LY MG MW ML MR MU MA MZ NA NE NG RW ST SN SC SL SO ZA SS SD TZ TG TN UG ZM ZW'],
  EU: ['Europe', 'AL AD AT BY BE BA BG HR CY CZ DK EE FI FR DE GR HU IS IE IT LV LI LT LU MT MD MC ME NL MK NO PL PT RO RU SM RS SK SI ES SE CH UA GB VA'],
  AS: ['Asie', 'AF AM AZ BH BD BT BN KH CN GE IN ID IR IQ JP JO KZ KW KG LA LB MY MV MN MM NP KP OM PK PS PH QA SA SG KR LK SY TJ TH TL TR TM AE UZ VN YE'],
  NA: ['Amérique du Nord', 'AG BS BB BZ CA CR CU DM DO SV GD GT HT HN JM MX NI PA KN LC VC TT US'],
  SA: ['Amérique du Sud', 'AR BO BR CL CO EC GY PY PE SR UY VE'],
  OC: ['Océanie', 'AU FJ KI MH FM NR NZ PW PG WS SB TO TV VU']
};
const continentOf = {};
for (const [k, [, list]] of Object.entries(CONTINENTS)) list.split(' ').forEach(c => {
  if (continentOf[c]) throw new Error('Continent en double: ' + c);
  continentOf[c] = k;
});

const rm = d => fs.rmSync(d, { recursive: true, force: true });
const mk = d => fs.mkdirSync(d, { recursive: true });
['vendor', 'flags', 'data', 'data/cities', 'fonts'].forEach(d => { rm(path.join(www, d)); mk(path.join(www, d)); });

fs.copyFileSync(nm('d3/dist/d3.min.js'), path.join(www, 'vendor/d3.min.js'));
fs.copyFileSync(nm('topojson-client/dist/topojson-client.min.js'), path.join(www, 'vendor/topojson-client.min.js'));
fs.copyFileSync(nm('heic-to/dist/iife/heic-to.js'), path.join(www, 'vendor/heic-to.js'));

const list = SOVEREIGN.map(a2 => {
  const name = NAME_FIX[a2] || countries.getName(a2, 'fr', { select: 'official' }) || countries.getName(a2, 'fr');
  const num = countries.alpha2ToNumeric(a2);
  if (!name || !num) throw new Error('Pays inconnu: ' + a2);
  fs.copyFileSync(nm(`flag-icons/flags/4x3/${a2.toLowerCase()}.svg`), path.join(www, `flags/${a2.toLowerCase()}.svg`));
  if (!continentOf[a2]) throw new Error('Continent manquant: ' + a2);
  return { c: a2, n: num, name, k: continentOf[a2] };
}).sort((a, b) => a.name.localeCompare(b.name, 'fr'));

// Régions d'outre-mer / îles séparées du pays principal : id world-atlas ou boîtes [lonMin, latMin, lonMax, latMax]
const isle = (x, y, d) => [[x - d, y - d, x + d, y + d]];
const SUBS = [
  ['FR-GF', 'FR', 'Guyane', { b: [[-55, 1.5, -51, 6.5]], f: 'gf' }],
  ['FR-GP', 'FR', 'Guadeloupe', { b: [[-61.9, 15.8, -60.9, 16.6]], f: 'gp' }],
  ['FR-MQ', 'FR', 'Martinique', { b: [[-61.3, 14.3, -60.7, 15]], f: 'mq' }],
  ['FR-RE', 'FR', 'La Réunion', { b: [[55, -21.5, 56, -20.8]], f: 're' }],
  ['FR-YT', 'FR', 'Mayotte', { b: [[44.9, -13.1, 45.4, -12.5]], f: 'yt' }],
  ['FR-NC', 'FR', 'Nouvelle-Calédonie', { id: '540', f: 'nc' }],
  ['FR-PF', 'FR', 'Polynésie française', { id: '258', f: 'pf' }],
  ['FR-PM', 'FR', 'Saint-Pierre-et-Miquelon', { id: '666', f: 'pm' }],
  ['FR-WF', 'FR', 'Wallis-et-Futuna', { id: '876', f: 'wf' }],
  ['FR-MF', 'FR', 'Saint-Martin', { id: '663', f: 'mf' }],
  ['FR-BL', 'FR', 'Saint-Barthélemy', { id: '652', f: 'bl' }],
  ['FR-TF', 'FR', 'Terres australes françaises', { id: '260', f: 'tf' }],
  ...[['TF', 'Tenerife', -16.54, 28.29], ['LZ', 'Lanzarote', -13.64, 29.03], ['FV', 'Fuerteventura', -14.04, 28.41], ['GC', 'Grande Canarie', -15.58, 27.96],
    ['GO', 'La Gomera', -17.21, 28.11], ['HI', 'El Hierro', -18.01, 27.74], ['LP', 'La Palma', -17.85, 28.70]]
    .map(([k, n, x, y]) => [`ES-CN-${k}`, 'ES', n, { b: isle(x, y, 0.3), f: 'ic', g: 'Îles Canaries' }]),
  ...[['MA', 'Majorque', 2.96, 39.63], ['ME', 'Minorque', 4.09, 39.97], ['IB', 'Ibiza', 1.42, 38.99], ['FO', 'Formentera', 1.48, 38.70]]
    .map(([k, n, x, y]) => [`ES-IB-${k}`, 'ES', n, { b: isle(x, y, 0.12), g: 'Îles Baléares' }]),
  ...[['SM', 'São Miguel', -25.51, 37.80], ['MA', 'Santa Maria', -25.11, 36.98], ['TE', 'Terceira', -27.21, 38.72], ['SJ', 'São Jorge', -28.04, 38.63],
    ['PI', 'Pico', -28.35, 38.47], ['FA', 'Faial', -28.71, 38.58], ['FL', 'Flores', -31.21, 39.44]]
    .map(([k, n, x, y]) => [`PT-20-${k}`, 'PT', n, { b: isle(x, y, 0.12), g: 'Açores' }]),
  ['PT-30', 'PT', 'Madère', { b: [[-17.5, 32.3, -16, 33.3]] }],
  ...[
    ['FR-COR', 'FR', 'Corse', 9.10, 42.16], ['IT-SIC', 'IT', 'Sicile', 14.16, 37.59], ['IT-SAR', 'IT', 'Sardaigne', 9.04, 40.09],
    ['GR-CRE', 'GR', 'Crète', 24.86, 35.24, 'Îles grecques'], ['GR-RHO', 'GR', 'Rhodes', 27.95, 36.18, 'Îles grecques'],
    ['GR-COR', 'GR', 'Corfou', 19.84, 39.64, 'Îles grecques'], ['GR-LES', 'GR', 'Lesbos', 26.25, 39.17, 'Îles grecques'],
    ['GR-SAN', 'GR', 'Santorin', 25.43, 36.40, 'Îles grecques'], ['GR-MYK', 'GR', 'Mykonos', 25.37, 37.46, 'Îles grecques'],
    ['GR-NAX', 'GR', 'Naxos', 25.49, 37.06, 'Îles grecques'], ['GR-EUB', 'GR', 'Eubée', 23.79, 38.57, 'Îles grecques'],
    ['ID-BAL', 'ID', 'Bali', 115.13, -8.36], ['ID-LOM', 'ID', 'Lombok', 116.35, -8.58], ['TZ-ZAN', 'TZ', 'Zanzibar', 39.35, -6.14],
    ['KR-JEJ', 'KR', 'Jeju', 126.56, 33.38], ['CN-HAI', 'CN', 'Hainan', 109.74, 19.20], ['JP-OKI', 'JP', 'Okinawa', 127.96, 26.50],
    ['AU-TAS', 'AU', 'Tasmanie', 146.59, -42.01], ['CL-PAS', 'CL', 'Île de Pâques', -109.35, -27.12], ['DK-BOR', 'DK', 'Bornholm', 14.90, 55.14],
    ['SE-GOT', 'SE', 'Gotland', 18.50, 57.48], ['TH-PHU', 'TH', 'Phuket', 98.34, 7.97], ['TH-SAM', 'TH', 'Koh Samui', 100.00, 9.51],
    ['MY-LAN', 'MY', 'Langkawi', 99.79, 6.37], ['GB-WIG', 'GB', 'Île de Wight', -1.30, 50.68], ['HR-KRK', 'HR', 'Krk', 14.62, 45.07],
    ['MX-COZ', 'MX', 'Cozumel', -86.91, 20.44]
  ].map(([c, p, n, x, y, g]) => [c, p, n, { b: isle(x, y, 0.1), g }]),
  ['US-AK', 'US', 'Alaska', { b: [[-180, 50, -129, 72], [170, 50, 180, 56]] }],
  ['US-HI', 'US', 'Hawaï', { b: [[-161, 18.5, -154, 22.5]] }],
  ['US-PR', 'US', 'Porto Rico', { id: '630', f: 'pr' }],
  ['US-GU', 'US', 'Guam', { id: '316', f: 'gu' }],
  ['US-VI', 'US', 'Îles Vierges des États-Unis', { id: '850', f: 'vi' }],
  ['US-AS', 'US', 'Samoa américaines', { id: '016', f: 'as' }],
  ['US-MP', 'US', 'Îles Mariannes du Nord', { id: '580', f: 'mp' }],
  ['GB-BM', 'GB', 'Bermudes', { id: '060', f: 'bm' }],
  ['GB-KY', 'GB', 'Îles Caïmans', { id: '136', f: 'ky' }],
  ['GB-VG', 'GB', 'Îles Vierges britanniques', { id: '092', f: 'vg' }],
  ['GB-TC', 'GB', 'Îles Turques-et-Caïques', { id: '796', f: 'tc' }],
  ['GB-AI', 'GB', 'Anguilla', { id: '660', f: 'ai' }],
  ['GB-MS', 'GB', 'Montserrat', { id: '500', f: 'ms' }],
  ['GB-FK', 'GB', 'Îles Malouines', { id: '238', f: 'fk' }],
  ['GB-JE', 'GB', 'Jersey', { id: '832', f: 'je' }],
  ['GB-GG', 'GB', 'Guernesey', { id: '831', f: 'gg' }],
  ['GB-IM', 'GB', 'Île de Man', { id: '833', f: 'im' }],
  ['GB-SH', 'GB', 'Sainte-Hélène', { id: '654', f: 'sh' }],
  ['GB-PN', 'GB', 'Îles Pitcairn', { id: '612', f: 'pn' }],
  ['GB-GS', 'GB', 'Géorgie du Sud', { id: '239', f: 'gs' }],
  ['GB-IO', 'GB', "Territoire britannique de l'océan Indien", { id: '086', f: 'io' }],
  ['DK-GL', 'DK', 'Groenland', { id: '304', f: 'gl' }],
  ['DK-FO', 'DK', 'Îles Féroé', { id: '234', f: 'fo' }],
  ['NL-AW', 'NL', 'Aruba', { id: '533', f: 'aw' }],
  ['NL-CW', 'NL', 'Curaçao', { id: '531', f: 'cw' }],
  ['NL-SX', 'NL', 'Saint-Martin (Pays-Bas)', { id: '534', f: 'sx' }],
  ['NL-BQ', 'NL', 'Pays-Bas caribéens', { b: [[-69, 11.9, -62.9, 18.2]], f: 'bq' }],
  ['NO-SJ', 'NO', 'Svalbard', { b: [[10, 74, 35, 81]], f: 'sj' }],
  ['EC-W', 'EC', 'Îles Galápagos', { b: [[-92.2, -1.6, -89, 0.8]] }],
  ['CN-HK', 'CN', 'Hong Kong', { id: '344', f: 'hk' }],
  ['CN-MO', 'CN', 'Macao', { id: '446', f: 'mo' }],
  ['FI-AX', 'FI', 'Åland', { id: '248', f: 'ax' }],
  ['NZ-CK', 'NZ', 'Îles Cook', { id: '184', f: 'ck' }],
  ['NZ-NU', 'NZ', 'Niue', { id: '570', f: 'nu' }],
  ['AU-NF', 'AU', 'Île Norfolk', { id: '574', f: 'nf' }]
].map(([c, p, name, o]) => {
  if (!SOVEREIGN.includes(p)) throw new Error('Parent inconnu: ' + c);
  let f = o.f && fs.existsSync(nm(`flag-icons/flags/4x3/${o.f}.svg`)) ? o.f : null;
  if (f) fs.copyFileSync(nm(`flag-icons/flags/4x3/${f}.svg`), path.join(www, `flags/${f}.svg`));
  return { c, p, name, id: o.id, b: o.b, g: o.g, f: f || p.toLowerCase() };
});

const topo = JSON.parse(fs.readFileSync(nm('world-atlas/countries-50m.json'), 'utf8'));
const ids = new Set(topo.objects.countries.geometries.map(g => g.id));
const missing = list.filter(c => !ids.has(c.n)).map(c => c.c);

const extra = Object.keys(continentOf).filter(c => !SOVEREIGN.includes(c));
if (extra.length) throw new Error('Continent pour pays inconnu: ' + extra);
// version allégée du 50m (mêmes pays et îles, moins de points) pour le globe en mouvement
const simp = require('topojson-simplify');
const topoMid = simp.presimplify(JSON.parse(fs.readFileSync(nm('world-atlas/countries-50m.json'), 'utf8')));
const topo110 = simp.simplify(topoMid, simp.quantile(topoMid, 0.2));
topo110.arcs = topo110.arcs.map(arc => arc.map(([x, y]) => [Math.round(x * 1000) / 1000, Math.round(y * 1000) / 1000]));
fs.writeFileSync(path.join(www, 'data/world.js'), 'window.NOMAD_WORLD=' + JSON.stringify(topo) + ';window.NOMAD_WORLD110=' + JSON.stringify(topo110) + ';');
fs.writeFileSync(path.join(www, 'data/continents.js'), 'window.NOMAD_CONTINENTS=' + JSON.stringify(Object.fromEntries(Object.entries(CONTINENTS).map(([k, [n]]) => [k, n]))) + ';');
fs.copyFileSync(nm('@fontsource-variable/outfit/files/outfit-latin-wght-normal.woff2'), path.join(www, 'fonts/outfit.woff2'));
fs.writeFileSync(path.join(www, 'data/countries.js'), 'window.NOMAD_COUNTRIES=' + JSON.stringify(list) + ';window.NOMAD_SUBS=' + JSON.stringify(SUBS) + ';');
// Villes (GeoNames, > 1000 habitants), un fichier par code pays
const byCc = {};
for (const c of require('all-the-cities')) {
  (byCc[c.country] ||= []).push(c);
}
let nCities = 0;
for (const [cc, list] of Object.entries(byCc)) {
  list.sort((a, b) => b.population - a.population);
  const out = list.map(c => [c.name, +c.loc.coordinates[0].toFixed(3), +c.loc.coordinates[1].toFixed(3)]);
  nCities += out.length;
  fs.writeFileSync(path.join(www, `data/cities/${cc}.json`), JSON.stringify(out));
}
console.log(`Villes: ${nCities} dans ${Object.keys(byCc).length} fichiers`);
console.log(`OK: ${list.length} pays, sans géométrie: ${missing.join(' ') || 'aucun'}`);
