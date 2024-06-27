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
    

def get_current_time():
    # Obtener la fecha y hora actual
    now = datetime.now()
    # Formatear la fecha y hora
    current_time = now.strftime("%Y-%m-%d %H:%M:%S")
    return current_time

def ValueError(value):
    return {
        'message': value
    }

def lambda_handler(event, context):
    
    # Nombre de la tabla
    user_table_name = 'DB_USER_LOGS_APP'
    # Obtener la tabla
    table_user = dynamodb.Table(user_table_name)
    
    request_body = event.get('body', '[]')  # Assuming JSON payload
    headers_request = event.get('headers', {})
    headers_str = json.dumps(headers_request)
   
    body_json = json.loads(request_body)

    key = {
        'idUser': body_json[0].get('user', 'null')
    }
    try:
        response = table_user.get_item(Key=key)
        
        # Procesar y devolver la respuesta
        items = response.get('Item','')

        if items == "":
            return respond(ValueError('El usuario no existe.'))
    
    except Exception as e:
        return respond(ValueError('Unsupported method "{}"'.format(str(e))))
    
    
    logs_table_name = 'DB_DATA_PREDICTION_LOGS_APP'
    # Obtener la tabla
    #table_logs= dynamodb.Table(logs_table_name)
    
    try:
        chunks = [body_json[i:i + 25] for i in range(0, len(body_json), 25)]
        #response = table_logs.put_item(Item=item)
        
        for chunk in chunks:
            with dynamodb.Table(logs_table_name).batch_writer() as batch:
                for item in chunk:
                    
                    batch.put_item(Item={
                        'idLog': str(uuid.uuid4()),  # Reemplaza 'primaryKey' con el nombre de tu clave primaria
                        'idUser': item.get('user', '-').lower(),  # Reemplaza 'primaryKey' con el nombre de tu clave primaria
                        'startDateTime': item.get('startDateTime', get_current_time).lower(),
                        'updateDateTime': item.get('updateDateTime', get_current_time).lower(),
                        'status': item.get('status', 'success').lower(),
                        'modelType': item.get('modelType', '-').lower(),
                        'ipClient': headers_request.get('x-forwarded-for', '0.0.0.0'),
                        'headersControl': headers_str,
                        'metadata': item.get('metadata', '-').lower()
                    })
        
        response = {
            "status": "OK",
            "message": "Se registro correctamente."
        }
        # Procesar y devolver la respuesta
        return respond(None, response)
    
    except Exception as e:
        return respond(ValueError('Unsupported method "{}"'.format(str(e))))
