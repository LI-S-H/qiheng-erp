const path = require('node:path');
const SwaggerParser = require('@apidevtools/swagger-parser');

const openapiPath = path.resolve(__dirname, '../../docs/api/erp-openapi.yaml');

SwaggerParser.validate(openapiPath)
  .then(api => {
    console.log(`OPENAPI_SCHEMA_OK: ${api.info.title} ${api.info.version}`);
  })
  .catch(error => {
    console.error(`OPENAPI_SCHEMA_INVALID: ${error.message}`);
    process.exitCode = 1;
  });
