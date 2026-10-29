"""Strict local operation dispatch. No arbitrary call names or executable paths."""
import logging
from fastapi import HTTPException
from pydantic import ValidationError
from agent import Command
from computer_actions import PrepareAction, ConfirmAction
from .policy import Request


class BrokerDispatch:
    def __init__(self, processes, actions):
        self.processes, self.actions = processes, actions

    def __call__(self, request):
        try:
            req = Request.model_validate(request)
            if req.op in ('health', 'snapshot', 'describe') and req.body:
                raise HTTPException(422, 'This operation does not accept arguments')
            if req.op == 'health': result = {'version': '0.5', 'ready': True}
            elif req.op == 'snapshot': result = self.processes.snapshot()
            elif req.op == 'process': result = self.processes.command(Command.model_validate(req.body))
            elif req.op == 'describe': result = self.actions.describe()
            elif req.op == 'prepare': result = self.actions.prepare(PrepareAction.model_validate(req.body).action)
            else: result = self.actions.execute(ConfirmAction.model_validate(req.body).confirmation)
            return {'status': 200, 'result': result}
        except ValidationError:
            return {'status': 422, 'detail': 'Invalid local operation or parameters'}
        except HTTPException as exc:
            return {'status': exc.status_code, 'detail': str(exc.detail)}
        except Exception:
            logging.exception('Local operation failed')
            return {'status': 500, 'detail': 'The local helper encountered an error. Check its log before repeating an action.'}

