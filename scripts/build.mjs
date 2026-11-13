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

const rm = d => fs.rmSync(d, { recursive: true, force: true });
const mk = d => fs.mkdirSync(d, { recursive: true });
['vendor', 'flags', 'data'].forEach(d => { rm(path.join(www, d)); mk(path.join(www, d)); });

fs.copyFileSync(nm('d3/dist/d3.min.js'), path.join(www, 'vendor/d3.min.js'));
fs.copyFileSync(nm('topojson-client/dist/topojson-client.min.js'), path.join(www, 'vendor/topojson-client.min.js'));

const list = SOVEREIGN.map(a2 => {
  const name = NAME_FIX[a2] || countries.getName(a2, 'fr', { select: 'official' }) || countries.getName(a2, 'fr');
  const num = countries.alpha2ToNumeric(a2);
  if (!name || !num) throw new Error('Pays inconnu: ' + a2);
  fs.copyFileSync(nm(`flag-icons/flags/4x3/${a2.toLowerCase()}.svg`), path.join(www, `flags/${a2.toLowerCase()}.svg`));
  return { c: a2, n: num, name };
}).sort((a, b) => a.name.localeCompare(b.name, 'fr'));

const topo = JSON.parse(fs.readFileSync(nm('world-atlas/countries-50m.json'), 'utf8'));
const ids = new Set(topo.objects.countries.geometries.map(g => g.id));
const missing = list.filter(c => !ids.has(c.n)).map(c => c.c);

fs.writeFileSync(path.join(www, 'data/world.js'), 'window.NOMAD_WORLD=' + JSON.stringify(topo) + ';');
fs.writeFileSync(path.join(www, 'data/countries.js'), 'window.NOMAD_COUNTRIES=' + JSON.stringify(list) + ';');
console.log(`OK: ${list.length} pays, sans géométrie: ${missing.join(' ') || 'aucun'}`);
