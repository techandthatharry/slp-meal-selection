const https = require('https');
const AUTH = 'Basic ' + Buffer.from('deven@techandthat.com:V3DbrM!m3swruY!').toString('base64');

function gql(query) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ query });
    const opts = {
      hostname: 'api-sandbox2.uk.arbor.sc',
      path: '/graphql/query',
      method: 'POST',
      headers: {
        'Authorization': AUTH,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'User-Agent': 'Mozilla/5.0 SLPApp/1.0',
        'Accept': 'application/json',
      },
    };
    const req = https.request(opts, (res) => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch (e) { console.log('Raw:', data.substring(0, 400)); resolve(null); }
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

function fields(typeName) {
  return gql(`{ __type(name: "${typeName}") { fields { name type { name kind ofType { name } } } } }`);
}

async function main() {
  // 1. Student type - look for dietary/allergy fields
  console.log('\n--- Student fields (dietary related) ---');
  const student = await fields('Student');
  if (student && student.data && student.data.__type) {
    student.data.__type.fields
      .filter(f => /diet|allerg|medical|health|food|special|requirement|condition/i.test(f.name))
      .forEach(f => {
        const t = f.type.name || (f.type.ofType && f.type.ofType.name) || f.type.kind;
        console.log(` ${f.name}: ${t}`);
      });
    console.log('-- ALL student fields:');
    student.data.__type.fields.forEach(f => {
      const t = f.type.name || (f.type.ofType && f.type.ofType.name) || f.type.kind;
      console.log(` ${f.name}: ${t}`);
    });
  }

  // 2. Check DietaryRequirement type
  console.log('\n--- DietaryRequirement type ---');
  const dr = await fields('DietaryRequirement');
  if (dr && dr.data && dr.data.__type) {
    dr.data.__type.fields.forEach(f => {
      const t = f.type.name || (f.type.ofType && f.type.ofType.name) || f.type.kind;
      console.log(` ${f.name}: ${t}`);
    });
  } else { console.log('Type not found'); }

  // 3. Check StudentDietaryRequirement type
  console.log('\n--- StudentDietaryRequirement type ---');
  const sdr = await fields('StudentDietaryRequirement');
  if (sdr && sdr.data && sdr.data.__type) {
    sdr.data.__type.fields.forEach(f => {
      const t = f.type.name || (f.type.ofType && f.type.ofType.name) || f.type.kind;
      console.log(` ${f.name}: ${t}`);
    });
  } else { console.log('Type not found'); }

  // 4. What input args does MealRotationMenuChoice accept? Can we filter by mealRotationMenu?
  console.log('\n--- MealRotationMenuChoice query args ---');
  const rmcArgs = await gql(`{ __schema { queryType { fields { name args { name type { name kind ofType { name } } } } } } }`);
  if (rmcArgs && rmcArgs.data && rmcArgs.data.__schema) {
    const mealFields = rmcArgs.data.__schema.queryType.fields.filter(f => /Meal/i.test(f.name));
    mealFields.forEach(f => {
      const args = f.args.map(a => {
        const t = a.type.name || (a.type.ofType && a.type.ofType.name) || a.type.kind;
        return `${a.name}: ${t}`;
      });
      console.log(` ${f.name}(${args.join(', ')})`);
    });
  }
}

main().catch(console.error);
