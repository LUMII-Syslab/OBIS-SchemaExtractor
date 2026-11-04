(function (root) {
    "use strict";

    const hasText = value => value != null && (typeof value !== "string" || value.trim().length > 0);

    function cleanArrays(value) {
        if (Array.isArray(value)) {
            const items = value.filter(hasText).map(cleanArrays).filter(item => item !== undefined);
            return items.length ? items : undefined;
        }
        if (value && Object.getPrototypeOf(value) === Object.prototype) {
            return Object.fromEntries(Object.entries(value)
                .map(([key, item]) => [key, cleanArrays(item)])
                .filter(([, item]) => item !== undefined));
        }
        return value; // Preserve File, Blob, FormData, numbers, booleans, and scalar strings.
    }

    function prepareRequest(request) {
        const result = { ...request, parameters: cleanArrays(request.parameters) || {} };
        let body = cleanArrays(request.requestBody);
        // Swagger assigns the multipart body inside executeRequest, after this wrapper runs.
        Object.defineProperty(result, "requestBody", {
            enumerable: true,
            configurable: true,
            get: () => body,
            set: value => { body = cleanArrays(value); }
        });
        return result;
    }

    function stringArrayEditor(Original, system) {
        const React = system.React;
        const buttonWrappers = new Map();
        return function (props) {
            const items = props.schema.get("items");
            if (!items || props.fn.getSchemaObjectType(items) !== "string"
                    || props.fn.isFileUploadIntended(items)) {
                return React.createElement(Original, props);
            }
            const getComponent = (name, ...args) => {
                const Component = props.getComponent(name, ...args);
                if (name !== "Button") return Component;
                if (!buttonWrappers.has(Component)) {
                    buttonWrappers.set(Component, buttonProps => React.createElement(Component, {
                        ...buttonProps,
                        children: (buttonProps.className || "").split(/\s+/).includes("json-schema-form-item-add")
                            ? "Add item (string)" : buttonProps.children
                    }));
                }
                return buttonWrappers.get(Component);
            };
            return React.createElement(Original, {
                ...props,
                getComponent,
                fn: { ...props.fn, getSampleSchema: () => "" }
            });
        };
    }

    root.ObisStringArraysPlugin = function () {
        return {
            wrapComponents: { JsonSchema_array: stringArrayEditor },
            statePlugins: {
                spec: {
                    wrapActions: {
                        executeRequest: original => request => original(prepareRequest(request))
                    }
                }
            }
        };
    };

    if (typeof module === "object" && module.exports) {
        module.exports = { plugin: root.ObisStringArraysPlugin, cleanArrays, prepareRequest };
    }
})(globalThis);
