const { test } = require('node:test');
const assert = require('node:assert/strict');
const { plugin, cleanArrays, prepareRequest } = require('../../main/resources/swagger-ui/string-arrays.js');

test('cleans arrays of any parameter name, omits empty arrays and preserves real values', () => {
    const file = new Blob(['data']);
    const original = { futureParameter: ['', '  ', null, '\t\n', '\u00a0', 'urn:keep:'],
        empty: [' ', ''], numbers: [0, 2], flags: [false], files: [file], scalar: ' ' };
    const result = cleanArrays(original);
    assert.deepEqual(result.futureParameter, ['urn:keep:']);
    assert.equal('empty' in result, false);
    assert.deepEqual(result.numbers, [0, 2]);
    assert.deepEqual(result.flags, [false]);
    assert.equal(result.files[0], file);
    assert.equal(result.scalar, ' ');
    assert.equal(original.futureParameter.length, 6);
});

test('cleans parameters and the multipart body assigned later by Swagger', () => {
    const request = prepareRequest({ parameters: { 'query.futureList': [' ', 'value'], 'query.empty': [''] } });
    assert.deepEqual(request.parameters, { 'query.futureList': ['value'] });
    request.requestBody = { futureBodyList: [' ', 'body'], empty: ['', '\t'] };
    assert.deepEqual({ ...request }.requestBody, { futureBodyList: ['body'] });
});

function editor(itemSchema) {
    const Original = function Original() {};
    const Button = function Button() {};
    const system = { React: { createElement: (component, props) => ({ component, props }) } };
    const props = {
        schema: new Map([['items', itemSchema]]),
        fn: { getSchemaObjectType: item => item.type,
            isFileUploadIntended: item => item.format === 'binary', getSampleSchema: () => 'string' },
        getComponent: () => Button
    };
    return { props, element: plugin().wrapComponents.JsonSchema_array(Original, system)(props) };
}

test('recognizes string item schemas, starts blank, changes only the add button', () => {
    const { element } = editor({ type: 'string' });
    assert.equal(element.props.fn.getSampleSchema(), '');
    const Button = element.props.getComponent('Button');
    assert.equal(Button({ className: 'btn json-schema-form-item-add', children: 'Add string item' }).props.children,
        'Add item (string)');
    assert.equal(Button({ className: 'btn', children: '-' }).props.children, '-');
    assert.equal(element.props.getComponent('Button'), Button);
});

test('does not customize file or numeric arrays', () => {
    for (const schema of [{ type: 'string', format: 'binary' }, { type: 'integer' }]) {
        const { props, element } = editor(schema);
        assert.equal(element.props, props);
    }
});

test('wraps the request execution action before serialization', () => {
    const action = plugin().statePlugins.spec.wrapActions.executeRequest(request => request);
    const result = action({ parameters: { arbitrary: ['', ' ', 'kept'] } });
    assert.deepEqual(result.parameters, { arbitrary: ['kept'] });
});
