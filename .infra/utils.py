class DictWrapper(dict):

    def __getitem__(self, key):
        return self.__getattr__(key)

    def __getattr__(self, name):
        result = super().get(name)
        if result is None:
            return DictWrapper({})
        if isinstance(result, dict):
            wrapped = DictWrapper(result)
            self[name] = wrapped
            return wrapped
        if isinstance(result, list):
            for i, item in enumerate(result):
                if isinstance(item, dict):
                    result[i] = DictWrapper(item)
            return result
        return result


def camel_case(s: str, separator: str = "-") -> str:
    return "".join(part.capitalize() for part in s.split(separator))
