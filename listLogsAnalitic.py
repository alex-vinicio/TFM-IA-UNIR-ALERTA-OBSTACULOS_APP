import boto3
import json

dynamodb = boto3.resource('dynamodb')


def respond(err, res=None):
    return {
        'statusCode': '400' if err else '200',
        'body': err.message if err else json.dumps(res),
        'headers': {
            'Content-Type': 'application/json',
        },
    }


def lambda_handler(event, context):
    
    # Nombre de la tabla
    table_name = 'DB_DATA_PREDICTION_LOGS_APP'
    
    # Obtener la tabla
    table = dynamodb.Table(table_name)
    
    try:
        response = table.scan()
        
        # Procesar y devolver la respuesta
        items = response['Items']
        return respond(None, items)
    
    except Exception as e:
        return respond(ValueError('Unsupported method "{}"'.format(operation)))
