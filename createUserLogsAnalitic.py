import boto3
import json
import uuid
from datetime import datetime

dynamodb = boto3.resource('dynamodb')


def respond(err, res=None):
    return {
        'statusCode': '400' if err else '200',
        'body': json.dumps(err) if err else json.dumps(res),
        'headers': {
            'Content-Type': 'application/json',
        },
    }
    
    
def ValueError(value):
    return {
        'message': value
    }


def get_current_time():
    # Obtener la fecha y hora actual
    now = datetime.now()
    # Formatear la fecha y hora
    current_time = now.strftime("%Y-%m-%d %H:%M:%S")
    return current_time


def lambda_handler(event, context):
    
    # Nombre de la tabla
    table_name = 'DB_USER_LOGS_APP'
    
    # Obtener la tabla
    table = dynamodb.Table(table_name)
    
    request_body = event.get('body', {})  # Assuming JSON payload
    headers_request = event.get('headers', {})
   
    body_json = json.loads(request_body)
    
    
    # Generar un UID
    uid = str(uuid.uuid4())
    
    deviceInfo = headers_request.get('sec-ch-ua-platform', '') + " - " + headers_request.get('sec-ch-ua', '')
    
    item = {
        'idUser': uid,  # Reemplaza 'primaryKey' con el nombre de tu clave primaria
        'status': body_json.get('status', 'active').lower(),
        'ip': headers_request.get('x-forwarded-for', '0.0.0.0'),
        'createdAt': get_current_time(),
        'updateAt': get_current_time(),
        'name': body_json.get('name', headers_request.get('user-agent', 'unknown user')),
        'description': headers_request.get('user-agent', 'unknown user'),
        'deviceIp': deviceInfo.lower()
    }
    
    try:
        # Guardar el ítem en la tabla
        response = table.put_item(Item=item)
        
        response = {
            "user": uid,
            "message": "Se registro correctamente."
        }
        # Procesar y devolver la respuesta
        return respond(None, response)
    
    except Exception as e:
        return respond(ValueError('Unsupported method "{}"'.format(operation)))
