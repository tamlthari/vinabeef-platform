import asyncio
import logging
import sys

from amqtt.broker import Broker

port = int(sys.argv[1])
cfg = {"listeners": {"default": {"type": "tcp", "bind": f"127.0.0.1:{port}"}}, "sys_interval": 0,
       "auth": {"allow-anonymous": True, "plugins": ["auth_anonymous"]}, "topic-check": {"enabled": False}}
async def main():
    b = Broker(cfg); await b.start(); await asyncio.Event().wait()
logging.basicConfig(level=logging.ERROR)
asyncio.run(main())
