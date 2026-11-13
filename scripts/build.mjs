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
['vendor', 'flags', 'data', 'fonts'].forEach(d => { rm(path.join(www, d)); mk(path.join(www, d)); });

fs.copyFileSync(nm('d3/dist/d3.min.js'), path.join(www, 'vendor/d3.min.js'));
fs.copyFileSync(nm('topojson-client/dist/topojson-client.min.js'), path.join(www, 'vendor/topojson-client.min.js'));

const list = SOVEREIGN.map(a2 => {
  const name = NAME_FIX[a2] || countries.getName(a2, 'fr', { select: 'official' }) || countries.getName(a2, 'fr');
  const num = countries.alpha2ToNumeric(a2);
  if (!name || !num) throw new Error('Pays inconnu: ' + a2);
  fs.copyFileSync(nm(`flag-icons/flags/4x3/${a2.toLowerCase()}.svg`), path.join(www, `flags/${a2.toLowerCase()}.svg`));
  if (!continentOf[a2]) throw new Error('Continent manquant: ' + a2);
  return { c: a2, n: num, name, k: continentOf[a2] };
}).sort((a, b) => a.name.localeCompare(b.name, 'fr'));

const topo = JSON.parse(fs.readFileSync(nm('world-atlas/countries-50m.json'), 'utf8'));
const ids = new Set(topo.objects.countries.geometries.map(g => g.id));
const missing = list.filter(c => !ids.has(c.n)).map(c => c.c);

const extra = Object.keys(continentOf).filter(c => !SOVEREIGN.includes(c));
if (extra.length) throw new Error('Continent pour pays inconnu: ' + extra);
const topo110 = JSON.parse(fs.readFileSync(nm('world-atlas/countries-110m.json'), 'utf8'));
fs.writeFileSync(path.join(www, 'data/world.js'), 'window.NOMAD_WORLD=' + JSON.stringify(topo) + ';window.NOMAD_WORLD110=' + JSON.stringify(topo110) + ';');
fs.writeFileSync(path.join(www, 'data/continents.js'), 'window.NOMAD_CONTINENTS=' + JSON.stringify(Object.fromEntries(Object.entries(CONTINENTS).map(([k, [n]]) => [k, n]))) + ';');
fs.copyFileSync(nm('@fontsource-variable/outfit/files/outfit-latin-wght-normal.woff2'), path.join(www, 'fonts/outfit.woff2'));
fs.writeFileSync(path.join(www, 'data/countries.js'), 'window.NOMAD_COUNTRIES=' + JSON.stringify(list) + ';');
console.log(`OK: ${list.length} pays, sans géométrie: ${missing.join(' ') || 'aucun'}`);
