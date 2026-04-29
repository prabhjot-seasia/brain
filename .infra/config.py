from pathlib import Path

import yaml

from utils import DictWrapper

environment = None
env = None


def _load_config():
    raw = yaml.safe_load(Path("config.yaml").read_text())
    configuration = DictWrapper(raw)

    for key in configuration.environments:
        configuration.environments[key]["name"] = key

    default_env = configuration.environments.get("default", DictWrapper({}))

    configuration["environments"] = [
        DictWrapper({**default_env, **env_def})
        for env_def in configuration.environments.values()
        if env_def.name != "default"
    ]

    return configuration


configuration = _load_config()
environments = list(configuration.environments)
